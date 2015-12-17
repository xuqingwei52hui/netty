/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AsyncMapping;
import io.netty.util.CharsetUtil;
import io.netty.util.DomainNameMapping;
import io.netty.util.Mapping;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.IDN;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;

/**
 * <p>Enables <a href="https://tools.ietf.org/html/rfc3546#section-3.1">SNI
 * (Server Name Indication)</a> extension for server side SSL. For clients
 * support SNI, the server could have multiple host name bound on a single IP.
 * The client will send host name in the handshake data so server could decide
 * which certificate to choose for the host name. </p>
 */
public class SniHandler extends ByteToMessageDecoder implements ChannelOutboundHandler {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SniHandler.class);

    private final AsyncMapping<String, SslContext> mapping;

    private boolean suppressRead;
    private boolean readPending;
    private boolean handshaken;
    private volatile String hostname;
    private volatile SslContext selectedContext;

    /**
     * Creates a SNI detection handler with configured {@link SslContext}
     * maintained by {@link Mapping}
     *
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    @SuppressWarnings("unchecked")
    public SniHandler(final Mapping<? super String, ? extends SslContext> mapping) {
        this(new AsyncMappingAdapter(mapping));
    }

    /**
     * Creates a SNI detection handler with configured {@link SslContext}
     * maintained by {@link DomainNameMapping}
     *
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    public SniHandler(DomainNameMapping<? extends SslContext> mapping) {
        this((Mapping<String, ? extends SslContext>) mapping);
    }

    /**
     * Creates a SNI detection handler with configured {@link SslContext}
     * maintained by {@link AsyncMapping}
     *
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    @SuppressWarnings("unchecked")
    public SniHandler(final AsyncMapping<? super String, ? extends SslContext> mapping) {
        this.mapping = (AsyncMapping<String, SslContext>) ObjectUtil.checkNotNull(mapping, "mapping");
    }

    /**
     * @return the selected hostname
     */
    public String hostname() {
        return hostname;
    }

    /**
     * @return the selected {@link SslContext}
     */
    public SslContext sslContext() {
        return selectedContext;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!handshaken && in.readableBytes() >= 5) {
            String hostname = sniHostNameFromHandshakeInfo(in);
            if (hostname != null) {
                hostname = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.US);
            }
            this.hostname = hostname;

            Future<SslContext> future = mapping.map(hostname, ctx.executor().<SslContext>newPromise());
            if (future.isDone()) {
                if (future.isSuccess()) {
                    selectContext(ctx, future);
                } else {
                    throw new DecoderException(future.cause());
                }
            } else {
                suppressRead = true;
                future.addListener(new FutureListener<SslContext>() {
                    @Override
                    public void operationComplete(Future<SslContext> future) throws Exception {
                        suppressRead = false;
                        if (future.isSuccess()) {
                            selectContext(ctx, future);
                        } else {
                            ctx.fireExceptionCaught(new DecoderException(future.cause()));
                        }
                        if (readPending) {
                            readPending = false;
                            ctx.read();
                        }
                    }
                });
            }
        }
    }

    private void selectContext(ChannelHandlerContext ctx, Future<SslContext> f) {
        selectedContext = f.getNow();
        if (handshaken) {
            SslHandler sslHandler = selectedContext.newHandler(ctx.alloc());
            ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
        }
    }

    private String sniHostNameFromHandshakeInfo(ByteBuf in) {
        int readerIndex = in.readerIndex();
        try {
            int command = in.getUnsignedByte(readerIndex);

            // tls, but not handshake command
            switch (command) {
                case SslConstants.SSL_CONTENT_TYPE_CHANGE_CIPHER_SPEC:
                case SslConstants.SSL_CONTENT_TYPE_ALERT:
                case SslConstants.SSL_CONTENT_TYPE_APPLICATION_DATA:
                    return null;
                case SslConstants.SSL_CONTENT_TYPE_HANDSHAKE:
                    break;
                default:
                    //not tls or sslv3, do not try sni
                    handshaken = true;
                    return null;
            }

            int majorVersion = in.getUnsignedByte(readerIndex + 1);

            // SSLv3 or TLS
            if (majorVersion == 3) {

                int packetLength = in.getUnsignedShort(readerIndex + 3) + 5;

                if (in.readableBytes() >= packetLength) {
                    // decode the ssl client hello packet
                    // we have to skip some var-length fields
                    int offset = readerIndex + 43;

                    int sessionIdLength = in.getUnsignedByte(offset);
                    offset += sessionIdLength + 1;

                    int cipherSuitesLength = in.getUnsignedShort(offset);
                    offset += cipherSuitesLength + 2;

                    int compressionMethodLength = in.getUnsignedByte(offset);
                    offset += compressionMethodLength + 1;

                    int extensionsLength = in.getUnsignedShort(offset);
                    offset += 2;
                    int extensionsLimit = offset + extensionsLength;

                    while (offset < extensionsLimit) {
                        int extensionType = in.getUnsignedShort(offset);
                        offset += 2;

                        int extensionLength = in.getUnsignedShort(offset);
                        offset += 2;

                        // SNI
                        if (extensionType == 0) {
                            handshaken = true;
                            int serverNameType = in.getUnsignedByte(offset + 2);
                            if (serverNameType == 0) {
                                int serverNameLength = in.getUnsignedShort(offset + 3);
                                return in.toString(offset + 5, serverNameLength,
                                        CharsetUtil.UTF_8);
                            } else {
                                // invalid enum value
                                return null;
                            }
                        }

                        offset += extensionLength;
                    }

                    handshaken = true;
                    return null;
                } else {
                    // client hello incomplete
                    return null;
                }
            } else {
                handshaken = true;
                return null;
            }
        } catch (Throwable e) {
            // unexpected encoding, ignore sni and use default
            if (logger.isDebugEnabled()) {
                logger.debug("Unexpected client hello packet: " + ByteBufUtil.hexDump(in), e);
            }
            handshaken = true;
            return null;
        }
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        ctx.bind(localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.disconnect();
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.close(promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        ctx.deregister(promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (suppressRead) {
            readPending = true;
        } else {
            ctx.read();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    private static final class AsyncMappingAdapter implements AsyncMapping<String, SslContext> {
        private final Mapping<? super String, ? extends SslContext> mapping;

        private AsyncMappingAdapter(Mapping<? super String, ? extends SslContext> mapping) {
            this.mapping = ObjectUtil.checkNotNull(mapping, "mapping");
        }

        @Override
        public Future<SslContext> map(String input, Promise<SslContext> promise) {
            final SslContext context;
            try {
                context = mapping.map(input);
            } catch (Throwable cause) {
                return promise.setFailure(cause);
            }
            return promise.setSuccess(context);
        }
    }
}
