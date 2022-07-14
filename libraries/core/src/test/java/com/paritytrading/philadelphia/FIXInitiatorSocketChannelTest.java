/*
 * Copyright 2022 Philadelphia authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.paritytrading.philadelphia;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

class FIXInitiatorSocketChannelTest extends FIXInitiatorTest {
    @Override
    protected Channels createChannels() throws IOException {
        ServerSocketChannel acceptorServerChannel = ServerSocketChannel.open();
        acceptorServerChannel.bind(null);

        SocketChannel initiator = SocketChannel.open(acceptorServerChannel.getLocalAddress());
        initiator.configureBlocking(false);

        SocketChannel acceptor = acceptorServerChannel.accept();
        acceptor.configureBlocking(false);

        Channels channels = new Channels();
        channels.initiatorRead = initiator;
        channels.initiatorWrite = initiator;
        channels.acceptor = acceptor;

        acceptorServerChannel.close();
        return channels;
    }
}
