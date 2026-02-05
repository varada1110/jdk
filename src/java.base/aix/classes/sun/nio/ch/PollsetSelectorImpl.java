/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jdk.internal.misc.Blocker;

import static sun.nio.ch.EPoll.EPOLLIN;
import static sun.nio.ch.EPoll.EPOLL_CTL_ADD;
import static sun.nio.ch.EPoll.EPOLL_CTL_DEL;
import static sun.nio.ch.EPoll.EPOLL_CTL_MOD;


/**
 * AIX pollset based Selector implementation
 */

class PollsetSelectorImpl extends SelectorImpl {


    // file descriptors
    private final int pfd0;
    private final int pfd1;

    // poll object
    Pollset pollWrapper;

    // address of poll array when polling with epoll_wait
    private final long pollArrayAddress;

    // eventfd object used for interrupt
    private final EventFD eventfd;

    // maps file descriptor to selection key, synchronize on selector
    private final Map<Integer, SelectionKeyImpl> fdToKey = new HashMap<>();

    // True if this Selector has been closed
    private boolean closed = false;

    // pending new registrations/updates, queued by setEventOps
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();

    // interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered = false;

    PollsetSelectorImpl(SelectorProvider sp) throws IOException {

        try {
            this.eventfd = new EventFD();
            IOUtil.configureBlocking(IOUtil.newFD(eventfd.efd()), false);
        } catch (IOException ioe) {
            throw ioe;
        }

        Pollset.pollsetCtl(pollsetFD, PS_ADD, POLLIN, eventfd.efd());

    }

    @Override
    protected int doSelect(Consumer<SelectionKey> action, long timeout)
            throws IOException
    {
        if (closed)
            throw new ClosedSelectorException();

        boolean blocking = (timeout != 0);

        processDeregisterQueue();
        try {
            begin(blocking);
            boolean attempted = Blocker.begin(blocking);
            try {
                pollWrapper.poll(timeout);
            } finally {
                Blocker.end(attempted);
            }
        } finally {
            end(blocking);
        }


        processDeregisterQueue();
        int numKeysUpdated = updateSelectedKeys();
        if (pollWrapper.getReventOps(0) != 0) {
            synchronized (interruptLock) {
                IOUtil.drain(fd0);
                interruptTriggered = false;
            }
        }
        return numKeysUpdated;
    }

    /**
     * Update the keys whose fd's have been selected by the pollset.
     * Add the ready keys to the ready queue.
     */
    private int updateSelectedKeys() {
        int entries = pollWrapper.updated;
        int numKeysUpdated = 0;
        for (int i=0; i<entries; i++) {
            int nextFD = pollWrapper.getDescriptor(i);
            SelectionKeyImpl ski = (SelectionKeyImpl) fdToKey.get(
                    new Integer(nextFD));
            // ski is null in the case of an interrupt
            if (ski != null) {
                int rOps = pollWrapper.getEventOps(i);
                if (selectedKeySet().contains(ski)) {
                    if (((SelChImpl) ski.channel()).translateAndSetReadyOps(rOps, ski)) {
                        numKeysUpdated++;
                    }
                } else {
                    ((SelChImpl) ski.channel()).translateAndSetReadyOps(rOps, ski);
                    if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                        selectedKeySet().add(ski);
                        numKeysUpdated++;
                    }
                }
            }
        }
        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        assert Thread.holdsLock(this);

        if (closed)
            return;
        closed = true;

        // Prevent further wakeups
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        // Close wakeup pipe
        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);

        if (pollWrapper != null) {

            // Remove wakeup fd from pollset (best-effort)
            pollWrapper.release(fd0);

            // Close pollset FD
            pollWrapper.closePollsetFD();

            // Free native poll array / state
            pollWrapper = null;
            setSelectedKeySet(null);

            // Deregister all keys
            Iterator<SelectionKey> it = keySet().iterator();
            while (it.hasNext()) {
                SelectionKeyImpl ski = (SelectionKeyImpl) it.next();
                deregister(ski);

                SelectableChannel ch = ski.channel();
                if (!ch.isOpen() && !ch.isRegistered()) {
                    ((SelChImpl) ch).kill();
                }
                it.remove();
            }
        }

        fd0 = -1;
        fd1 = -1;
    }


    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid();
        assert Thread.holdsLock(this);

        int fd = ski.getFDVal();

        if (fdToKey.remove(fd) != null) {
            // Remove fd from pollset
            Pollset.pollsetCtl(pollset, Pollset.PS_DELETE, fd, 0);

            // Clear registered events (for consistency with other selectors)
            ski.registeredEvents(0);
        } else {
            assert ski.registeredEvents() == 0;
        }
    }

    @Override
    public void setEventOps(SelectionKeyImpl ski) {
        synchronized (updateLock) {
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    IOUtil.write1(sp[1], (byte) 0);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                interruptTriggered = true;
            }
        }
        return this;
    }


    private void clearInterrupt() throws IOException {
        synchronized (interruptLock) {
            IOUtil.drain(fd0Val);
            interruptTriggered = false;
        }
    }
}
