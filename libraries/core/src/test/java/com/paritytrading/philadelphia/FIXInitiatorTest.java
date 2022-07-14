/*
 * Copyright 2015 Philadelphia authors
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

import static com.paritytrading.philadelphia.FIXConnectionStatus.*;
import static com.paritytrading.philadelphia.Strings.*;
import static com.paritytrading.philadelphia.fix42.FIX42Enumerations.*;
import static com.paritytrading.philadelphia.fix42.FIX42MsgTypes.*;
import static com.paritytrading.philadelphia.fix42.FIX42Tags.*;
import static java.util.Arrays.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value=1, unit=TimeUnit.SECONDS)
abstract class FIXInitiatorTest {

    private static final FIXConfig initiatorConfig = new FIXConfig.Builder()
        .setSenderCompID("initiator")
        .setTargetCompID("acceptor")
        .setFieldCapacity(1024)
        .build();

    private FIXMessages  initiatorMessages;
    private TestMessages acceptorMessages;

    private FIXConnectionStatus initiatorStatus;

    private FIXConnection initiator;
    private TestConnection acceptor;

    static final class Channels {
        public ReadableByteChannel initiatorRx;
        public GatheringByteChannel initiatorTx;
        public ReadableByteChannel acceptorRx;
        public GatheringByteChannel acceptorTx;
    }

    abstract Channels createChannels() throws IOException;

    @BeforeEach
    void setUp() throws IOException {
        initiatorMessages = new FIXMessages();
        acceptorMessages  = new TestMessages();

        initiatorStatus = new FIXConnectionStatus();

        Channels channels = createChannels();
        initiator = new FIXConnection(
                channels.initiatorRx, channels.initiatorTx,
                initiatorConfig, initiatorMessages, initiatorStatus
        );
        acceptor  = new TestConnection(channels.acceptorRx, channels.acceptorTx, acceptorMessages);
    }

    @Test
    void heartbeat() throws IOException {
        initiator.setCurrentTimeMillis(10_000);
        initiator.keepAlive();

        acceptor.send("35=0|34=1|");
        while (initiator.getIncomingMsgSeqNum() != 2)
            initiator.receive();

        initiator.setCurrentTimeMillis(35_000);
        initiator.keepAlive();

        String message = "8=FIX.4.2|9=60|35=0|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:35.000|10=223|";

        initiatorMessage(message);
    }

    @Test
    void testRequest() throws IOException {
        initiator.setCurrentTimeMillis(32_500);
        initiator.keepAlive();

        String heartbeat = "8=FIX.4.2|9=60|35=0|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:32.500|10=225|";

        initiator.setCurrentTimeMillis(35_000);
        initiator.keepAlive();

        String testRequest = "8=FIX.4.2|9=86|35=1|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:35.000|112=19700101-00:00:35.000|10=213|";

        initiatorMessages(asList(heartbeat, testRequest));
    }

    @Test
    void testResponse() throws IOException {
        initiator.setCurrentTimeMillis(35_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());

        acceptor.send("35=0|34=1|");

        while (initiator.getIncomingMsgSeqNum() != 2)
            initiator.receive();

        initiator.setCurrentTimeMillis(60_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());

        initiator.setCurrentTimeMillis(70_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());

        initiator.setCurrentTimeMillis(75_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());
    }

    @Test
    void heartbeatTimeout() throws IOException {
        initiator.setCurrentTimeMillis(35_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());

        initiator.setCurrentTimeMillis(40_000);
        initiator.keepAlive();

        assertEquals(asList(), initiatorStatus.collect());

        initiator.setCurrentTimeMillis(70_000);
        initiator.keepAlive();

        assertEquals(asList(new HeartbeatTimeout()), initiatorStatus.collect());

        initiator.setCurrentTimeMillis(75_000);
        initiator.keepAlive();

        assertEquals(asList(new HeartbeatTimeout()), initiatorStatus.collect());
    }

    @Test
    void receiveMessageWithoutMsgType() throws IOException {
        String message = "34=1|";
        Event  status  = new Close("MsgType(35) not found");

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveMessageWithoutMsgSeqNum() throws IOException {
        String request  = "35=0|";
        String response = "8=FIX.4.2|9=87|35=5|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|58=MsgSeqNum(34) not found|10=117|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveMessageWithTooLowMsgSeqNum() throws IOException {
        initiator.setIncomingMsgSeqNum(2);

        String message = "35=0|34=1|";
        Event status   = new TooLowMsgSeqNum(1, 2);

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveMessageWithTooHighMsgSeqNum() throws IOException {
        String request  = "35=0|34=2|";
        String response = "8=FIX.4.2|9=69|35=2|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|7=1|16=0|10=093|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveTestRequest() throws IOException {
        String request  = "35=1|34=1|112=foo|";
        String response = "8=FIX.4.2|9=68|35=0|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|112=foo|10=245|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveTestRequestWithoutTestReqID() throws IOException {
        String request  = "35=1|34=1|";
        String response = "8=FIX.4.2|9=99|35=3|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|45=1|373=1|58=TestReqID(112) not found|10=092|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveResendRequestWithEndSeqNoSmallerThanLastMsgSeqNum() throws IOException {
        initiator.setOutgoingMsgSeqNum(5);

        String request  = "35=2|34=1|7=2|16=3|";
        String response = "8=FIX.4.2|9=71|35=4|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:00.000|123=Y|36=4|10=230|";

        acceptorRequestInitiatorResponse(request, response);
        assertEquals(5, initiator.getOutgoingMsgSeqNum());
    }

    @Test
    void receiveResendRequestWithEndSeqNoEqualToLastMsgSeqNum() throws IOException {
        initiator.setOutgoingMsgSeqNum(5);

        String request  = "35=2|34=1|7=2|16=4|";
        String response = "8=FIX.4.2|9=71|35=4|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:00.000|123=Y|36=5|10=231|";

        acceptorRequestInitiatorResponse(request, response);
        assertEquals(5, initiator.getOutgoingMsgSeqNum());
    }

    @Test
    void receiveResendRequestWithEndSeqNoLargerThanLastMsgSeqNum() throws IOException {
        initiator.setOutgoingMsgSeqNum(5);

        String request  = "35=2|34=1|7=2|16=6|";
        String response = "8=FIX.4.2|9=71|35=4|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:00.000|123=Y|36=5|10=231|";

        acceptorRequestInitiatorResponse(request, response);
        assertEquals(5, initiator.getOutgoingMsgSeqNum());
    }

    @Test
    void receiveResendRequestWithZeroEndSeqNo() throws IOException {
        initiator.setOutgoingMsgSeqNum(3);

        String request  = "35=2|34=1|7=2|16=0|";
        String response = "8=FIX.4.2|9=71|35=4|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:00.000|123=Y|36=3|10=229|";

        acceptorRequestInitiatorResponse(request, response);
        assertEquals(3, initiator.getOutgoingMsgSeqNum());
    }

    @Test
    void receiveResendRequestWithoutBeginSeqNo() throws IOException {
        String request  = "35=2|34=1|";
        String response = "8=FIX.4.2|9=98|35=3|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|45=1|373=1|58=BeginSeqNo(7) not found|10=116|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveResendRequestWithoutEndSeqNo() throws IOException {
        String request  = "35=2|34=1|7=1|";
        String response = "8=FIX.4.2|9=97|35=3|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|45=1|373=1|58=EndSeqNo(16) not found|10=213|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveResendRequestWithTooHighBeginSeqNo() throws IOException {
        String request  = "35=2|34=1|7=2|16=0|";
        String response = "8=FIX.4.2|9=97|35=3|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|45=1|373=5|58=BeginSeqNo(7) too high|10=252|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveResendRequestWithTooHighEndSeqNo() throws IOException {
        initiator.setOutgoingMsgSeqNum(3);

        String request  = "35=2|34=1|7=2|16=5|";
        String response = "8=FIX.4.2|9=71|35=4|49=initiator|56=acceptor|34=2|" +
            "52=19700101-00:00:00.000|123=Y|36=3|10=229|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveSequenceResetReset() throws IOException {
        String message = "35=4|34=1|36=5|";
        Event  status  = new SequenceReset();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveSequenceResetResetWithTooLowMsgSeqNum() throws IOException {
        initiator.setIncomingMsgSeqNum(2);

        String message = "35=4|34=1|36=5|";
        Event  status  = new SequenceReset();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveSequenceResetResetWithTooHighMsgSeqNum() throws IOException {
        String message = "35=4|34=2|36=5|";
        Event  status  = new SequenceReset();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveSequenceResetResetWithoutNewSeqNo() throws IOException {
        String request  = "35=4|34=1|";
        String response = "8=FIX.4.2|9=97|35=3|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|45=1|373=1|58=NewSeqNo(36) not found|10=234|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveSequenceResetGapFill() throws IOException {
        acceptor.send("35=4|34=1|123=Y|36=5|");

        while (initiator.getIncomingMsgSeqNum() != 5)
            initiator.receive();
    }

    @Test
    void receiveLogon() throws IOException {
        String message = "35=A|49=acceptor|56=initiator|34=1|";
        Event  status  = new Logon();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveLogout() throws IOException {
        String message = "35=5|34=1|";
        Event  status  = new Logout();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveLogoutWithTooLowMsgSeqNum() throws IOException {
        initiator.setIncomingMsgSeqNum(2);

        String message = "35=5|34=1|";
        Event  status  = new Logout();

        acceptorMessageInitiatorStatus(message, status);
    }

    @Test
    void receiveLogoutWithTooHighMsgSeqNum() throws IOException {
        String request  = "35=5|34=2|";
        String response = "8=FIX.4.2|9=69|35=2|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|7=1|16=0|10=093|";

        acceptorRequestInitiatorResponse(request, response);
    }

    @Test
    void receiveFullBuffer() throws IOException {
        acceptor.send(asList("35=5|34=1|58=" + repeat('A', 512) + "|",
                "35=5|34=2|58=" + repeat('A', 512) + "|"));

        while (initiator.getIncomingMsgSeqNum() != 3)
            initiator.receive();

        assertEquals(asList(), acceptorMessages.collect());
        assertEquals(asList(), initiatorMessages.collect());
        assertEquals(asList(new Logout(), new Logout()), initiatorStatus.collect());
    }

    @Test
    void receiveTooLongMessage() throws IOException {
        acceptor.send("35=5|34=1|58=" + repeat('A', 1024));

        assertThrows(FIXMessageOverflowException.class, () -> {
            while (true)
                initiator.receive();
        });
    }

    @Test
    void sendLogon() throws IOException {
        initiator.sendLogon(false);

        String logon = "8=FIX.4.2|9=72|35=A|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|98=0|108=30|10=004|";

        initiatorMessage(logon);
    }

    @Test
    void sendLogonWithResetSeqNumFlag() throws IOException {
        initiator.sendLogon(true);

        String logon = "8=FIX.4.2|9=78|35=A|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|98=0|108=30|141=Y|10=055|";

        initiatorMessage(logon);
    }

    @Test
    void sendLogout() throws IOException {
        initiator.sendLogout();

        String logout = "8=FIX.4.2|9=60|35=5|49=initiator|56=acceptor|34=1|" +
            "52=19700101-00:00:00.000|10=220|";

        initiatorMessage(logout);
    }

    @Test
    void sendApplicationMessage() throws IOException {
        initiator.sendLogon(false);

        FIXMessage message = initiator.create();

        initiator.prepare(message, OrderSingle);

        message.addField(ClOrdID).setInt(1);
        message.addField(HandlInst).setChar(HandlInstValues.AutomatedExecutionNoIntervention);
        message.addField(Symbol).setString("FOO");
        message.addField(Side).setChar(SideValues.Buy);
        message.addField(TransactTime).setString(initiator.getCurrentTimestamp());
        message.addField(OrderQty).setFloat(100.00, 2);
        message.addField(OrdType).setChar(OrdTypeValues.Limit);
        message.addField(Price).setFloat(25.50, 2);

        initiator.send(message);

        List<String> messages = asList("8=FIX.4.2|9=72|35=A|49=initiator|56=acceptor|34=1|" +
                "52=19700101-00:00:00.000|98=0|108=30|10=004|",
                "8=FIX.4.2|9=131|35=D|49=initiator|56=acceptor|34=2|52=19700101-00:00:00.000|" +
                "11=1|21=1|55=FOO|54=1|60=19700101-00:00:00.000|38=100.00|40=2|44=25.50|10=020|");

        initiatorMessages(messages);
    }

    private void initiatorMessage(String message) throws IOException {
        initiatorMessages(asList(message));
    }

    private void initiatorMessages(List<String> messages) throws IOException {
        while (acceptorMessages.collect().size() < messages.size())
            acceptor.receive();

        assertEquals(messages, acceptorMessages.collect());
        assertEquals(asList(), initiatorMessages.collect());
        assertEquals(asList(), initiatorStatus.collect());
    }

    private void acceptorRequestInitiatorResponse(String request, String response) throws IOException {
        acceptor.send(request);

        while (acceptorMessages.collect().size() < 1) {
            initiator.receive();
            acceptor.receive();
        }

        assertEquals(asList(response), acceptorMessages.collect());
        assertEquals(asList(), initiatorMessages.collect());
        assertEquals(asList(), initiatorStatus.collect());
    }

    private void acceptorMessageInitiatorStatus(String request, Event status) throws IOException {
        acceptor.send(request);

        while (initiatorStatus.collect().size() < 1)
            initiator.receive();

        assertEquals(asList(), acceptorMessages.collect());
        assertEquals(asList(), initiatorMessages.collect());
        assertEquals(asList(status), initiatorStatus.collect());
    }

}
