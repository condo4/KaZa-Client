package org.kaza;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * KaZa Protocol implementation in Java
 * Compatible with the C++/Qt KaZaProtocol class
 *
 * Frame structure:
 * - Byte 0: Frame type ID
 * - Bytes 1-4: Payload length (32-bit big-endian)
 * - Bytes 5+: Payload data
 */
public class KaZaProtocol {
    private static final String TAG = "KaZaProtocol";

    // Frame type constants (matching C++ KaZaProtocol)
    public static final byte FRAME_SYSTEM = 0;
    public static final byte FRAME_FILE = 1;
    public static final byte FRAME_OBJVALUE = 2;
    public static final byte FRAME_DBQUERY = 3;
    public static final byte FRAME_DBRESULT = 4;
    public static final byte FRAME_SOCKET_CONNECT = 5;
    public static final byte FRAME_SOCKET_DATA = 6;
    public static final byte FRAME_SOCKET_STATE = 7;
    public static final byte FRAME_VERSION = (byte) 255;

    // Protocol version constants
    public static final byte PROTOCOL_VERSION_MAJOR = 1;
    public static final byte PROTOCOL_VERSION_MINOR = 0;

    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final DataInputStream dataInputStream;
    private final DataOutputStream dataOutputStream;

    // Version negotiation state
    private boolean versionNegotiated = false;
    private byte peerProtocolMajor = 0;
    private byte peerProtocolMinor = 0;

    /**
     * Create a KaZa protocol handler for the given streams
     */
    public KaZaProtocol(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.dataInputStream = new DataInputStream(inputStream);
        this.dataOutputStream = new DataOutputStream(outputStream);
    }

    /**
     * Send a command string (FRAME_SYSTEM)
     * @param command The command string to send
     */
    public void sendCommand(String command) throws IOException {
        // Reduce logging verbosity for PING to save battery
        if (!command.equals("PING")) {
            Log.i(TAG, "KaZaProtocol: Sending command: " + command);
        }
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        sendFrame(FRAME_SYSTEM, payload);
        if (!command.equals("PING")) {
            Log.i(TAG, "KaZaProtocol: Command sent successfully");
        }
    }

    /**
     * Send a file (FRAME_FILE)
     * @param fileId 3-character file identifier
     * @param fileData File content
     */
    public void sendFile(String fileId, byte[] fileData) throws IOException {
        if (fileId.length() != 3) {
            throw new IllegalArgumentException("fileId must be exactly 3 characters");
        }

        // Format: "XXX:" + file data
        byte[] idBytes = fileId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + fileData.length];
        System.arraycopy(idBytes, 0, payload, 0, 3);
        payload[3] = ':';
        System.arraycopy(fileData, 0, payload, 4, fileData.length);

        sendFrame(FRAME_FILE, payload);
        Log.i(TAG, "KaZaProtocol: File sent: " + fileId + " (" + fileData.length + " bytes)");
    }

    /**
     * Send protocol version request (client side)
     * Payload format (matching C++ KaZaProtocol::sendVersion):
     * - Byte 0: Protocol major version
     * - Byte 1: Protocol major version (note: C++ sends major twice, likely a bug but we match it)
     * - Bytes 2+: Username (Qt QString format)
     * - Bytes N+: Device name (Qt QString format)
     * - Bytes M+: Channel (int32 big-endian)
     */
    public void sendVersion(String username, String devicename, int channel) throws IOException {
        Log.i(TAG, "KaZaProtocol: Sending VERSION request: " + PROTOCOL_VERSION_MAJOR + "." + PROTOCOL_VERSION_MAJOR +
                   " " + username + " " + devicename + " " + channel);

        try {
            java.io.ByteArrayOutputStream byteStream = new java.io.ByteArrayOutputStream();
            DataOutputStream dataStream = new DataOutputStream(byteStream);

            // Write major version twice (matching C++ implementation which has a bug on line 207)
            dataStream.writeByte(PROTOCOL_VERSION_MAJOR);
            dataStream.writeByte(PROTOCOL_VERSION_MINOR);

            // Write username as Qt QString (length-prefixed UTF-16 string)
            writeQString(dataStream, username);

            // Write devicename as Qt QString (length-prefixed UTF-16 string)
            writeQString(dataStream, devicename);

            // Write channel as int32
            dataStream.writeInt(channel);

            byte[] payload = byteStream.toByteArray();
            sendFrame(FRAME_VERSION, payload);
        } catch (IOException e) {
            Log.e(TAG, "KaZaProtocol: Failed to send VERSION", e);
            throw e;
        }
    }

    /**
     * Write a QString in Qt format (big-endian length prefix + UTF-16BE data)
     * Qt uses QDataStream format: 4-byte length (in bytes, not characters) + UTF-16 data
     */
    private void writeQString(DataOutputStream stream, String str) throws IOException {
        if (str == null) {
            stream.writeInt(0xFFFFFFFF); // Qt null string marker
            return;
        }

        // Convert to UTF-16BE
        byte[] utf16Bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);

        // Write byte length (4 bytes, big-endian)
        stream.writeInt(utf16Bytes.length);

        // Write the UTF-16BE encoded string
        stream.write(utf16Bytes);
    }


    /**
     * Send a raw frame with the given type and payload
     * Frame structure: [type:1 byte][length:4 bytes big-endian][payload:N bytes]
     */
    private void sendFrame(byte frameType, byte[] payload) throws IOException {
        synchronized (outputStream) {
            // Frame type (1 byte)
            dataOutputStream.writeByte(frameType);

            // Payload length (4 bytes, big-endian)
            dataOutputStream.writeInt(payload.length);

            // Payload data
            dataOutputStream.write(payload);

            // Flush to ensure data is sent immediately
            dataOutputStream.flush();

            // Only log non-PING frames to reduce battery drain
            if (frameType != FRAME_SYSTEM || payload.length != 4 ||
                !new String(payload, StandardCharsets.UTF_8).equals("PING")) {
                Log.d(TAG, "KaZaProtocol: Frame sent - Type: " + frameType + ", Length: " + payload.length);
            }
        }
    }

    /**
     * Read a frame from the input stream
     * This is a BLOCKING call - it will wait for data to arrive
     * @return ReceivedFrame object containing type and payload
     * @throws IOException if read fails or timeout occurs
     */
    public ReceivedFrame readFrame() throws IOException {
        // Read frame type (1 byte) - BLOCKS until data available
        byte frameType = dataInputStream.readByte();

        // Read payload length (4 bytes, big-endian) - BLOCKS until data available
        int payloadLength = dataInputStream.readInt();

        if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) { // Max 10MB
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        // Read payload - BLOCKS until all data received
        byte[] payload = new byte[payloadLength];
        dataInputStream.readFully(payload);

        // Skip logging PING/PONG frames to reduce battery drain
        boolean isPingPong = (frameType == FRAME_SYSTEM && payloadLength == 4 &&
                             (new String(payload, StandardCharsets.UTF_8).equals("PING") ||
                              new String(payload, StandardCharsets.UTF_8).equals("PONG")));
        if (!isPingPong) {
            Log.i(TAG, "KaZaProtocol: Frame received - Type: " + frameType + ", Length: " + payloadLength);
        }

        return new ReceivedFrame(frameType, payload);
    }

    /**
     * Container class for received frames
     */
    public static class ReceivedFrame {
        public final byte frameType;
        public final byte[] payload;

        public ReceivedFrame(byte frameType, byte[] payload) {
            this.frameType = frameType;
            this.payload = payload;
        }

        /**
         * Get payload as UTF-8 string (for FRAME_SYSTEM)
         */
        public String getPayloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        /**
         * Check if this is a command frame
         */
        public boolean isCommand() {
            return frameType == FRAME_SYSTEM;
        }

        /**
         * Check if this is a file frame
         */
        public boolean isFile() {
            return frameType == FRAME_FILE;
        }

        /**
         * Check if this is a version frame
         */
        public boolean isVersion() {
            return frameType == FRAME_VERSION;
        }
    }

    /**
     * Close the streams
     */
    public void close() throws IOException {
        if (dataInputStream != null) {
            dataInputStream.close();
        }
        if (dataOutputStream != null) {
            dataOutputStream.close();
        }
    }
}
