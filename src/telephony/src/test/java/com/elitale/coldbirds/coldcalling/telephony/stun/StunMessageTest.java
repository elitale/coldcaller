package com.elitale.coldbirds.coldcalling.telephony.stun;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

class StunMessageTest {

    // STUN magic cookie defined in RFC 5389
    private static final int MAGIC_COOKIE = 0x2112A442;

    // ------------------------------------------------------------------
    // Binding Request
    // ------------------------------------------------------------------

    @Test
    void testBindingRequestHasCorrectMessageType() {
        byte[] msg = StunMessage.buildBindingRequest();
        // Bytes 0-1: message type — Binding Request = 0x0001
        int type = ((msg[0] & 0xFF) << 8) | (msg[1] & 0xFF);
        assertThat(type).isEqualTo(0x0001);
    }

    @Test
    void testBindingRequestHasZeroMessageLength() {
        byte[] msg = StunMessage.buildBindingRequest();
        // Bytes 2-3: message length (attributes only) = 0 (no attributes)
        int length = ((msg[2] & 0xFF) << 8) | (msg[3] & 0xFF);
        assertThat(length).isEqualTo(0);
    }

    @Test
    void testBindingRequestHasMagicCookie() {
        byte[] msg = StunMessage.buildBindingRequest();
        // Bytes 4-7: magic cookie = 0x2112A442
        int cookie = ByteBuffer.wrap(msg, 4, 4).getInt();
        assertThat(cookie).isEqualTo(MAGIC_COOKIE);
    }

    @Test
    void testBindingRequestHas12ByteTransactionId() {
        byte[] msg = StunMessage.buildBindingRequest();
        // Total header = 20 bytes: 2 type + 2 length + 4 cookie + 12 txid
        assertThat(msg).hasSize(20);
    }

    @Test
    void testBindingRequestHasRandomTransactionId() {
        byte[] msg1 = StunMessage.buildBindingRequest();
        byte[] msg2 = StunMessage.buildBindingRequest();
        // Transaction IDs should be different (with overwhelming probability)
        boolean allEqual = true;
        for (int i = 8; i < 20; i++) {
            if (msg1[i] != msg2[i]) { allEqual = false; break; }
        }
        assertThat(allEqual).isFalse();
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    @Test
    void testParseValidXorMappedAddress() {
        // Build a synthetic STUN Binding Success Response with XOR-MAPPED-ADDRESS
        // IP: 93.184.216.34 (example.com), port: 12345
        byte[] response = buildSyntheticResponse("93.184.216.34", 12345, new byte[12]);
        StunMessage.MappedAddress addr = StunMessage.parseMappedAddress(response).orElseThrow();
        assertThat(addr.ip()).isEqualTo("93.184.216.34");
        assertThat(addr.port()).isEqualTo(12345);
    }

    @Test
    void testParseWithWrongMessageTypeReturnsEmpty() {
        // Message type = 0x0001 (Request, not Response)
        byte[] bad = new byte[20];
        bad[0] = 0x00; bad[1] = 0x01;
        ByteBuffer.wrap(bad, 4, 4).putInt(MAGIC_COOKIE);
        assertThat(StunMessage.parseMappedAddress(bad)).isEmpty();
    }

    @Test
    void testParseTooShortReturnsEmpty() {
        assertThat(StunMessage.parseMappedAddress(new byte[10])).isEmpty();
    }

    @Test
    void testParseNullThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> StunMessage.parseMappedAddress(null));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Build a minimal STUN Binding Success Response (0x0101) containing one
     * XOR-MAPPED-ADDRESS attribute for the given IP + port.
     */
    private static byte[] buildSyntheticResponse(String ip, int port, byte[] txId) {
        // XOR-MAPPED-ADDRESS attribute: type=0x0020, length=8
        // family=0x01 (IPv4), xored port, xored IP
        int xoredPort  = port ^ (MAGIC_COOKIE >>> 16);
        String[] parts = ip.split("\\.");
        int ipInt = ((Integer.parseInt(parts[0]) & 0xFF) << 24)
                  | ((Integer.parseInt(parts[1]) & 0xFF) << 16)
                  | ((Integer.parseInt(parts[2]) & 0xFF) << 8)
                  |  (Integer.parseInt(parts[3]) & 0xFF);
        int xoredIp = ipInt ^ MAGIC_COOKIE;

        ByteBuffer buf = ByteBuffer.allocate(20 + 4 + 8); // header + attr header + attr value
        // STUN header
        buf.putShort((short) 0x0101);      // Binding Success Response
        buf.putShort((short) (4 + 8));     // message length (attribute header + value)
        buf.putInt(MAGIC_COOKIE);
        buf.put(txId.length == 12 ? txId : new byte[12]); // 12-byte tx ID

        // XOR-MAPPED-ADDRESS attribute
        buf.putShort((short) 0x0020);      // attribute type
        buf.putShort((short) 8);           // attribute length
        buf.put((byte) 0x00);              // reserved
        buf.put((byte) 0x01);             // family = IPv4
        buf.putShort((short) xoredPort);
        buf.putInt(xoredIp);
        return buf.array();
    }
}
