/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */
package org.forgerock.opendj.grizzly;

import java.util.concurrent.CancellationException;

import org.forgerock.opendj.ldap.spi.LdapMessages.LdapResponseMessage;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.forgerock.reactive.Completable;

final class LdapResponseMessageWriter implements Subscriber<LdapResponseMessage>, CompletionHandler {

    private final Connection<?> connection;
    private final Completable.Subscriber downstream;
    private Subscription upstream;

    LdapResponseMessageWriter(final Connection<?> connection, final Completable.Subscriber downstream) {
        this.connection = connection;
        this.downstream = downstream;
    }

    @Override
    public void onSubscribe(final Subscription s) {
        if (upstream != null) {
            s.cancel();
            return;
        }
        upstream = s;
        connection.notifyCanWrite(new WriteHandler() {
            @Override
            public void onWritePossible() throws Exception {
                final Subscription sub = upstream;
                if (sub != null) {
                    sub.request(1);
                }
            }

            @Override
            public void onError(final Throwable error) {
                LdapResponseMessageWriter.this.onError(error);
            }
        });
    }

    @Override
    public void onNext(final LdapResponseMessage message) {
        connection.write(message).addCompletionHandler(this);
    }

    @Override
    public void completed(final Object result) {
        final Subscription sub = upstream;
        if (sub != null) {
            sub.request(1);
        }
    }

    @Override
    public void cancelled() {
        failed(new CancellationException());
    }

    @Override
    public void failed(final Throwable error) {
        onError(error);
    }

    @Override
    public void updated(final Object result) {
        // Nothing to do
    }

    @Override
    public void onError(final Throwable error) {
        upstream.cancel();
        upstream = null;
        downstream.onError(error);
    }

    @Override
    public void onComplete() {
        upstream.cancel();
        upstream = null;
        downstream.onComplete();
    }
}
