/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, IBM Corp.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import jdk.internal.misc.Unsafe;

public class Pollset {

    static final short POLLIN       = 0x0001;
    static final short POLLOUT      = 0x0002;
    static final short POLLHUP      = 0x2000;
    static final short POLLERR      = 0x4000;
    static final short POLLNVAL     = (short) 0x8000;

    // commands
    static final short PS_ADD	  = 0;
    static final short PS_MOD     = 1;
    static final short PS_DELETE  = 2;

    // Miscellaneous pollfd constants
    static final short SIZE_POLLFD    = getPollfdSize();
    static final short FD_OFFSET      = 0;
    static final short EVENT_OFFSET   = getSizeOfInt();
    // short consumes 2 bytes in all 32 and 64 bit architecture
    static final short REVENT_OFFSET  = (short) (EVENT_OFFSET + 2);

    // Miscellaneous pollset_ctl constants
    static final short SIZE_POLLCTL        = getPollCtlSize();
    static final short CTL_CMD_OFFSET      = 0;
    static final short CTL_EVENTS_OFFSET   = 2;
    static final short CTL_FD_OFFSET  	   = 4;

    static final int NUM_POLLCTLEVENTS = 64;
    static final int NUM_POLLSETEVENTS  = getFDLimit();

    static final int CTL_EVENTS_ARRAY_SIZE =
            NUM_POLLCTLEVENTS * SIZE_POLLCTL;
    static final int POLL_EVENTS_ARRAY_SIZE =
            NUM_POLLSETEVENTS * SIZE_POLLFD;

    // Base address of the native pollArray
    private final long pollArrayAddress;

    // Set of "idle" file descriptors
    private final HashSet<Integer> idleSet;

    private final Object pollsetUpdatorLock = new Object();

    private int pollsetUpdatorCount = 0;

    // Keeps track of events count, to be polled
    private int pollsetNumEvents = 0;

    // The pollfd array for results from pollset_poll
    private AllocatedNativeObject pollArray;

    // The pollset_ctl current array for adding the events into pollset
    private AllocatedNativeNode pollCtlArrayCurrent;

    // Points to the HEAD of the list
    private AllocatedNativeNode pollCtlArrayHead;

    // The fd of the pollset driver
    final int pollsetFD;

    // File descriptor to write for interrupt
    int interruptFD;

    // Number of updated pollfd entries
    int updated;

    PollsetArrayWrapper() {
     /* Creates the pollset file descriptor.
      * This native method invokes the pollset_create() system call, which
      * takes an integer param for maxfds - the maximum number of file
      * descriptors that may be registered with this pollset. When a value
      * of -1 is used, the maximum number of file descriptors that can belong
      * to the pollset is bound by OPEN_MAX as defined in <sys/limits.h>
      */
     pollsetFD = pollsetCreate(-1);

     // the pollfd array passed to pollset_poll()
     pollArray = new AllocatedNativeObject(POLL_EVENTS_ARRAY_SIZE, true);
     pollArrayAddress = pollArray.address();

     pollCtlArrayHead = pollCtlArrayCurrent =
             new AllocatedNativeNode(CTL_EVENTS_ARRAY_SIZE, true);

     // create idle set
     idleSet = new HashSet<Integer>();
    }

    /*
     * on 64 bit machine it returns -1(when it exceeds int limit).
     * In this case the fd limit is set to default.
     */
    private static int getFDLimit() {
     int limit = fdLimit();
     if(limit <=0) {
      return 8192;
     }
     return Math.min(limit, 8192);
    }

    void initInterrupt(int fd0, int fd1) {
     interruptFD = fd1;
     pollsetCtl(pollsetFD, PS_ADD, POLLIN, fd0);
    }


    // Access methods for fd structures
    int getEventOps(int i) {
     int offset = SIZE_POLLFD * i + EVENT_OFFSET;
     return pollArray.getShort(offset);
    }

    int getReventOps(int i) {
     int offset = SIZE_POLLFD * i + REVENT_OFFSET;
     short revents = pollArray.getShort(offset);
     return getPollRevents(revents);
    }

    int getDescriptor(int i) {
     int offset = SIZE_POLLFD * i + FD_OFFSET;
     return pollArray.getInt(offset);
    }

    /* Access methods for pollset_ctl structures. Careful while using these
     * functions. These should be synchronized against pollsetUpdatorLock.
     * Currently this is not synchronized as caller of these method is only
     * updateRegistration method and callers of this method are taking care
     * of synchronization.
     */
    private void putCtlEventsOps(int i, short events) {
     int offset = SIZE_POLLCTL * i + CTL_EVENTS_OFFSET;
     pollCtlArrayCurrent.putShort(offset, events);
    }

    private void putCtlCmdOps(int i, short cmd) {
     int offset = SIZE_POLLCTL * i + CTL_CMD_OFFSET;
     pollCtlArrayCurrent.putShort(offset, cmd);
    }

    private void putCtlFD(int i, int fd) {
     int offset = SIZE_POLLFD * i + CTL_FD_OFFSET;
     pollCtlArrayCurrent.putInt(offset, fd);
    }

    /**
     * Update the events for a given file descriptor.
     */
    void setInterest(int fd, int mask) {
     synchronized (pollsetUpdatorLock) {
      // if the interest events are 0 then add to idle set, and delete
      // from pollset if registered (or pending)
      if (mask == 0) {
       if (idleSet.add(fd)) {
        updateRegistration(PS_DELETE, (short)0, fd);
       }
       return;
      }
      // if file descriptor is idle then add to pollset
      if (!idleSet.isEmpty() && idleSet.remove(fd)) {
       updateRegistration(PS_ADD, (short)mask, fd);
       return;
      }
      // update existing registration..This can be done in two steps
      // 1. Remove the earlier registration
      // 2. Add the current interest against this fd
      updateRegistration(PS_DELETE, (short)0, fd);
      updateRegistration(PS_ADD, (short)mask, fd);
     }
    }

    /**
     * Add a new file descriptor to pollset
     */
    void add(int fd) {
     synchronized (pollsetUpdatorLock) {
      updateRegistration(PS_ADD, (short)0, fd);
     }
    }

    /**
     * Remove a file descriptor from pollset
     */
    void release(int fd) {
     synchronized (pollsetUpdatorLock) {
      // if file descriptor is idle then remove from idle set, otherwise
      // delete from pollset
      if (!idleSet.remove(fd)) {
       updateRegistration(PS_DELETE, (short)0, fd);
      }
     }
    }

    /**
     * Close pollset file descriptor and free pollfd array
     */
    void closePollsetFD() throws IOException {
     synchronized (pollsetUpdatorLock) {
      pollsetDestroy(pollsetFD);
      pollArray.free();
      AllocatedNativeNode.free(pollCtlArrayHead);
      pollCtlArrayHead = null;
      pollCtlArrayCurrent = null;
     }
    }

    /**
     * Method to flush all buffered events to OS
     */
    private void flushBulkPollCtlEvents() {
     synchronized (pollsetUpdatorLock) {
      // return if no events to flush
      if (pollsetNumEvents <= 0) {
       return;
      }

      int eventsToBeFlushed = pollsetNumEvents;
      int remainingEvents = pollsetNumEvents;
      AllocatedNativeNode pollCtlArrayToBeFlushed = pollCtlArrayHead;
      while ( remainingEvents > 0 ) {
       eventsToBeFlushed = (remainingEvents >= NUM_POLLCTLEVENTS) ? NUM_POLLCTLEVENTS : remainingEvents;
       remainingEvents -= eventsToBeFlushed;
       pollsetBulkCtl(pollsetFD, (pollCtlArrayToBeFlushed.address()), eventsToBeFlushed);
       pollCtlArrayToBeFlushed = pollCtlArrayToBeFlushed.getNext();
      }
      // Reset all the poll set variables
      pollsetNumEvents = pollsetUpdatorCount = 0;
      pollCtlArrayCurrent = pollCtlArrayHead;

     }
    }

    int poll(long timeout) throws IOException {
     // flush all accumulated events to the OS
     flushBulkPollCtlEvents();
     updated = pollsetPoll(pollsetFD, pollArrayAddress, NUM_POLLSETEVENTS, timeout);
     return updated;
    }

    /* Be carefull while calling this function. This should be synchronized
     * against pollsetUpdatorLock. Currently this is not synchronized as the
     * callers of this method are synchronized against pollsetUpdatorLock.
     */
    private void updateRegistration(short cmd, short events, int fd) {
     if (pollsetUpdatorCount == NUM_POLLCTLEVENTS) {
      // create a new poll buffer or reuse if already present
      if (pollCtlArrayCurrent.getNext() != null) {
       pollCtlArrayCurrent = pollCtlArrayCurrent.getNext();
      } else {
       AllocatedNativeNode newPollCtlArray =
               new AllocatedNativeNode(CTL_EVENTS_ARRAY_SIZE, true);
       pollCtlArrayCurrent.setNext(newPollCtlArray);
       pollCtlArrayCurrent = newPollCtlArray;
      }
      pollsetUpdatorCount = 0;
     }
     putCtlCmdOps(pollsetUpdatorCount, cmd);
     putCtlEventsOps(pollsetUpdatorCount, getAIXEvent(events));
     putCtlFD(pollsetUpdatorCount++, fd);
     pollsetNumEvents++;

    }

    private static short getAIXEvent(short event) {
     if((event & Net.POLLIN) != 0) {
      event |= POLLIN;
     }
     if((event & Net.POLLOUT) !=  0) {
      event |= POLLOUT;
     }
     if((event & Net.POLLERR) != 0) {
      event |= POLLERR;
     }
     if((event & Net.POLLHUP) != 0) {
      event |= POLLHUP;
     }
     if((event & Net.POLLNVAL) != 0) {
      event |= POLLNVAL;
     }

     return event;
    }

    private static short getPollRevents(short revents) {
     if((revents & POLLIN) !=0) {
      revents |= Net.POLLIN;
     }
     if((revents & POLLOUT) !=0) {
      revents |= Net.POLLOUT;
     }
     if((revents & POLLERR) !=0) {
      revents |= Net.POLLERR;
     }
     if((revents & POLLHUP) !=0) {
      revents |= Net.POLLHUP;
     }
     if((revents & POLLNVAL) !=0) {
      revents |= Net.POLLNVAL;
     }
     return revents;
    }

    public void interrupt() {
     interrupt(interruptFD);
    }

    static {
     init();
    }

    // -- Native methods --
    public static native int pollsetCreate() throws IOException;
    public static native int pollsetCtl(int pollset, int opcode, int fd, int events);
    public static native int pollsetPoll(int pollset, long pollAddress, int numfds, int timeout)
        throws IOException;
    public static native void pollsetDestroy(int pollset);
    public static native void init();
    public static native int eventSize();
    public static native int eventsOffset();
    public static native int reventsOffset();
    public static native int fdOffset();
    public static native void socketpair(int[] sv) throws IOException;
    public static native void interrupt(int fd) throws IOException;
    public static native void drain1(int fd) throws IOException;
    public static native void close0(int fd);

    private static class AllocatedNativeNode extends AllocatedNativeObject {

       private AllocatedNativeNode nextNode = null;

       AllocatedNativeNode(int size, boolean pageAligned) {
          super(size, pageAligned);
       }

       void setNext(AllocatedNativeNode next) {
          this.nextNode = next;
       }

       AllocatedNativeNode getNext() {
          return this.nextNode;
       }

        static void free(AllocatedNativeNode node) {
            AllocatedNativeNode toBeFreed = node;
            while(toBeFreed != null) {
                AllocatedNativeNode next = toBeFreed.getNext();
                toBeFreed.free();
                toBeFreed = next;
            }
        }
    }

}
