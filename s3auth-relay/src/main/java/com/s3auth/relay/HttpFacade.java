/**
 * Copyright (c) 2012, s3auth.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the s3auth.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.s3auth.relay;

import com.jcabi.log.Logger;
import com.jcabi.log.VerboseRunnable;
import com.jcabi.log.VerboseThreads;
import com.s3auth.hosts.Hosts;
import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * HTTP facade (port listener).
 *
 * <p>The class is instantiated in {@link Main}, once per application run.
 *
 * <p>The class is immutable and thread-safe.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 0.0.1
 * @see Main
 */
@SuppressWarnings("PMD.DoNotUseThreads")
final class HttpFacade implements Closeable {

    /**
     * How many threads to use.
     */
    private static final int THREADS =
        Runtime.getRuntime().availableProcessors() * 8;

    /**
     * Executor service, with socket openers.
     */
    private final transient ScheduledExecutorService frontend =
        Executors.newSingleThreadScheduledExecutor(new VerboseThreads("front"));

    /**
     * Executor service, with consuming threads.
     */
    private final transient ScheduledExecutorService backend =
        Executors.newScheduledThreadPool(
            HttpFacade.THREADS,
            new VerboseThreads("back")
        );

    /**
     * Blocking queue of ready-to-be-processed sockets.
     */
    private final transient BlockingQueue<Socket> sockets =
        new SynchronousQueue<Socket>();

    /**
     * Server socket.
     */
    private final transient ServerSocket server;

    /**
     * Public ctor.
     * @param hosts Hosts
     * @param port Port number
     * @throws IOException If can't initialize
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public HttpFacade(final Hosts hosts, final int port)
        throws IOException {
        this.server = new ServerSocket(port);
        for (int thread = 0; thread < HttpFacade.THREADS; ++thread) {
            this.backend.scheduleWithFixedDelay(
                new VerboseRunnable(new HttpThread(this.sockets, hosts)),
                0, 1, TimeUnit.NANOSECONDS
            );
        }
        Logger.debug(this, "#HttpFacade(.., %d): instantiated", port);
    }

    /**
     * Start listening to the port.
     */
    public void listen() {
        this.frontend.scheduleWithFixedDelay(
            new VerboseRunnable(
                // @checkstyle AnonInnerLength (50 lines)
                new Runnable() {
                    public void run() {
                        Socket socket;
                        try {
                            socket = HttpFacade.this.server.accept();
                        } catch (java.io.IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                        try {
                            final boolean consumed = HttpFacade.this.sockets
                                .offer(socket, 1, TimeUnit.SECONDS);
                            if (!consumed) {
                                new HttpResponse().withStatus(
                                    HttpURLConnection.HTTP_GATEWAY_TIMEOUT
                                ).send(socket);
                                socket.close();
                            }
                        } catch (java.io.IOException ex) {
                            throw new IllegalStateException(ex);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    }
                }
            ),
            0, 1, TimeUnit.NANOSECONDS
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        this.shutdown(this.frontend);
        this.shutdown(this.backend);
        this.server.close();
        Logger.debug(this, "#close(): done");
    }

    /**
     * Shutdown a service.
     * @param service The service to shut down
     */
    private void shutdown(final ScheduledExecutorService service) {
        service.shutdown();
        try {
            if (service.awaitTermination(1, TimeUnit.SECONDS)) {
                Logger.debug(this, "#shutdown(): succeeded");
            } else {
                Logger.warn(this, "#shutdown(): failed");
                service.shutdownNow();
                if (service.awaitTermination(1, TimeUnit.SECONDS)) {
                    Logger.info(this, "#shutdown(): shutdownNow() succeeded");
                } else {
                    Logger.error(this, "#shutdown(): failed to stop threads");
                }
            }
        } catch (InterruptedException ex) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}