package network.elrond.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

import static java.util.Arrays.copyOfRange;
import static network.elrond.core.ByteUtil.byteArrayToInt;
import static org.spongycastle.util.Arrays.concatenate;
import static org.spongycastle.util.BigIntegers.asUnsignedByteArray;

import java.util.List;

/**
 * Recursive Length Prefix (RLP) encoding.
 *
 * The purpose of RLP is to encode arbitrarily nested arrays of binary data, and
 * RLP is the main encoding method used to serialize objects in Ethereum. The
 * only purpose of RLP is to encode structure; encoding specific atomic data
 * types (eg. strings, ints, floats) is left up to higher-order protocols; in
 * Ethereum the standard is that integers are represented in big endian binary
 * form. If one wishes to use RLP to encode a dictionary, the two suggested
 * canonical forms are to either use [[k1,v1],[k2,v2]...] with keys in
 * lexicographic order or to use the higher-level Patricia Tree encoding as
 * Ethereum does.
 *
 * The RLP encoding function takes in an item. An item is defined as follows:
 *
 * - A string (ie. byte array) is an item - A listToTable of items is an item
 *
 * For example, an empty string is an item, as is the string containing the word
 * "cat", a listToTable containing any number of strings, as well as more complex data
 * structures like ["cat",["puppy","cow"],"horse",[[]],"pig",[""],"sheep"]. Note
 * that in the context of the rest of this article, "string" will be used as a
 * synonym for "a certain number of bytes of binary data"; no special encodings
 * are used and no knowledge about the content of the strings is implied.
 *
 * See: https://github.com/ethereum/wiki/wiki/%5BEnglish%5D-RLP
 *
 * www.ethereumJ.com
 * @author: Roman Mandeleil
 * Created on: 01/04/2014 10:45
 *
 */

/**
 * The RLP class implements Recursive Length Prefix encoding/decoding model.
 * Based on Ethereum Team work!
 *
 * @author  Elrond Team - JLS
 * @version 1.0
 * @since   2018-05-14
 */
public class RLP {
/*
    public static final int ONE_ITEM_THRESHOLD = 0x80;


    public static byte[] encode(Object objData) {
        if (objData instanceof Integer)
        {
            int data = (Integer)objData;

            if (data < 0) {

            }

            if (data < ONE_ITEM_THRESHOLD){

            }
        }

        return(null);
    }
*/
    /** Allow for content up to size of 2^64 bytes **/
    private static double MAX_ITEM_LENGTH = Math.pow(256, 8);

    /**
     * Reason for threshold according to Vitalik Buterin:
     * 	- 56 bytes maximizes the benefit of both options
     * 	- if we went with 60 then we would have only had 4 slots for long strings
     * so RLP would not have been able to store objects above 4gb
     * 	- if we went with 48 then RLP would be fine for 2^128 space, but that's way too much
     * 	- so 56 and 2^64 space seems like the right place to setAccountState the cutoff
     * 	- also, that's where Bitcoin's varint does the cutof
     **/
    private static int SIZE_THRESHOLD = 56;

    /**
     * [0x80]
     * If a string is 0-55 bytes long, the RLP encoding consists of a single
     * byte with value 0x80 plus the length of the string followed by the
     * string. The range of the first byte is thus [0x80, 0xb7].
     */
    private static int OFFSET_SHORT_ITEM = 0x80;

    /**
     * [0xb7]
     * If a string is more than 55 bytes long, the RLP encoding consists of a
     * single byte with value 0xb7 plus the length of the length of the string
     * in binary form, followed by the length of the string, followed by the
     * string. For example, a length-1024 string would be encoded as
     * \xb9\x04\x00 followed by the string. The range of the first byte is thus
     * [0xb8, 0xbf].
     */
    private static int OFFSET_LONG_ITEM = 0xb7;

    /**
     * [0xc0]
     * If the total payload of a listToTable (i.e. the combined length of all its
     * items) is 0-55 bytes long, the RLP encoding consists of a single byte
     * with value 0xc0 plus the length of the listToTable followed by the concatenation
     * of the RLP encodings of the items. The range of the first byte is thus
     * [0xc0, 0xf7].
     */
    private static int OFFSET_SHORT_LIST = 0xc0;

    /**
     * [0xf7]
     * If the total payload of a listToTable is more than 55 bytes long, the RLP
     * encoding consists of a single byte with value 0xf7 plus the length of the
     * length of the listToTable in binary form, followed by the length of the listToTable,
     * followed by the concatenation of the RLP encodings of the items. The
     * range of the first byte is thus [0xf8, 0xff].
     */
    private static int OFFSET_LONG_LIST = 0xf7;

    /* ******************************************************
     * 						DECODING						*
     * ******************************************************/

    /**
     * Reads any RLP encoded byte-array and returns all objects as byte-array or listToTable of byte-arrays
     *
     * @param data RLP encoded byte-array
     * @param pos position in the array to start reading
     * @return DecodeResult encapsulates the decoded items as a single Object and the final read position
     */
    public static DecodeResult decode(byte[] data, int pos) {
        if (data == null || data.length < 1) {
            return null;
        }
        int prefix = data[pos] & 0xFF;
        if (prefix == OFFSET_SHORT_ITEM) {
            return new DecodeResult(pos+1, ""); // means no length or 0
        } else if (prefix < OFFSET_SHORT_ITEM) {
            return new DecodeResult(pos+1, new byte[] { data[pos] }); // byte is its own RLP encoding
        } else if (prefix < OFFSET_LONG_ITEM) {
            int len = prefix - OFFSET_SHORT_ITEM; // length of the encoded bytes
            return new DecodeResult(pos+1+len, copyOfRange(data, pos+1, pos+1+len));
        } else if (prefix < OFFSET_SHORT_LIST) {
            int lenlen = prefix - OFFSET_LONG_ITEM; // length of length the encoded bytes
            int lenbytes = byteArrayToInt(copyOfRange(data, pos+1, pos+1+lenlen)); // length of encoded bytes
            return new DecodeResult(pos+1+lenlen+lenbytes, copyOfRange(data, pos+1+lenlen, pos+1+lenlen+lenbytes));
        } else if (prefix <= OFFSET_LONG_LIST) {
            int len = prefix - OFFSET_SHORT_LIST; // length of the encoded listToTable
            int prevPos = pos; pos++;
            return decodeList(data, pos, prevPos, len);
        } else if (prefix < 0xFF) {
            int lenlen = prefix - OFFSET_LONG_LIST; // length of length the encoded listToTable
            int lenlist = byteArrayToInt(copyOfRange(data, pos+1, pos+1+lenlen)); // length of encoded bytes
            pos = pos + lenlen + 1; // start at position of first element in listToTable
            int prevPos = lenlist;
            return decodeList(data, pos, prevPos, lenlist);
        } else {
            throw new RuntimeException("Only byte values between 0x00 and 0xFF are supported, but got: " + prefix);
        }
    }

    private static DecodeResult decodeList(byte[] data, int pos, int prevPos, int len) {
        List<Object> slice = new ArrayList<>();
        for (int i = 0; i < len;) {
            // Get the next item in the data listToTable and append it
            DecodeResult result = decode(data, pos);
            slice.add(result.getDecoded());
            // Increment pos by the amount bytes in the previous read
            prevPos = result.getPos();
            i += (prevPos - pos);
            pos = prevPos;
        }
        return new DecodeResult(pos, slice.toArray());
    }

    /* ******************************************************
     * 						ENCODING						*
     * ******************************************************/

    /**
     * Turn Object into its RLP encoded equivalent of a byte-array
     * Support for String, Integer, BigInteger and Lists of any of these types.
     *
     * @param input as object or List of objects
     * @return byte[] RLP encoded
     */
    public static byte[] encode(Object input) {
        Value val = new Value(input);
        if (val.isList()) {
            List<Object> inputArray = val.asList();
            if (inputArray.size() == 0) {
                return encodeLength(inputArray.size(), OFFSET_SHORT_LIST);
            }
            byte[] output = ByteUtil.EMPTY_BYTE_ARRAY;
            for (Object object : inputArray) {
                output = concatenate(output, encode(object));
            }
            byte[] prefix = encodeLength(output.length, OFFSET_SHORT_LIST);
            return concatenate(prefix, output);
        } else {
            byte[] inputAsBytes = toBytes(input);
            if (inputAsBytes.length == 1) {
                return inputAsBytes;
            } else {
                byte[] firstByte = encodeLength(inputAsBytes.length, OFFSET_SHORT_ITEM);
                return concatenate(firstByte, inputAsBytes);
            }
        }
    }

    /** Integer limitation goes up to 2^31-1 so length can never be bigger than MAX_ITEM_LENGTH */
    public static byte[] encodeLength(int length, int offset) {
        if (length < SIZE_THRESHOLD) {
            byte firstByte = (byte) (length + offset);
            return new byte[] { firstByte };
        } else if (length < MAX_ITEM_LENGTH) {
            byte[] binaryLength;
            if (length > 0xFF)
                binaryLength = BigInteger.valueOf(length).toByteArray();
            else
                binaryLength = new byte[] { (byte) length };
            byte firstByte = (byte) (binaryLength.length + offset + SIZE_THRESHOLD - 1);
            return concatenate(new byte[] { firstByte }, binaryLength);
        } else {
            throw new RuntimeException("Input too long");
        }
    }

    /*
     *	Utility function to convert Objects into byte arrays
     */
    private static byte[] toBytes(Object input) {
        if (input instanceof byte[]) {
            return (byte[]) input;
        } else if (input instanceof String) {
            String inputString = (String) input;
            return inputString.getBytes();
        } else if(input instanceof Long) {
            Long inputLong = (Long) input;
            return (inputLong == 0) ? ByteUtil.EMPTY_BYTE_ARRAY : asUnsignedByteArray(BigInteger.valueOf(inputLong));
        } else if(input instanceof Integer) {
            Integer inputInt = (Integer) input;
            return (inputInt == 0) ? ByteUtil.EMPTY_BYTE_ARRAY : asUnsignedByteArray(BigInteger.valueOf(inputInt.intValue()));
        } else if(input instanceof BigInteger) {
            BigInteger inputBigInt = (BigInteger) input;
            return (inputBigInt == BigInteger.ZERO) ? ByteUtil.EMPTY_BYTE_ARRAY : asUnsignedByteArray(inputBigInt);
        } else if (input instanceof Value) {
            Value val = (Value) input;
            return toBytes(val.asObj());
        }
        throw new RuntimeException("Unsupported type: Only accepting String, Integer and BigInteger for now");
    }

    public static byte[] encodeByte(byte singleByte) {
        if ((singleByte & 0xFF) == 0) {
            return new byte[] { (byte) OFFSET_SHORT_ITEM };
        } else if ((singleByte & 0xFF) < 0x7F) {
            return new byte[] { singleByte };
        } else {
            return new byte[] { (byte) (OFFSET_SHORT_ITEM + 1), singleByte };
        }
    }

    public static byte[] encodeElement(byte[] srcData) {

        if (srcData == null)
            return new byte[]{(byte) OFFSET_SHORT_ITEM};
        else if (srcData.length == 1 && (srcData[0] & 0xFF) < 0x80) {
            return srcData;
        } else if (srcData.length < SIZE_THRESHOLD) {
            // length = 8X
            byte length = (byte) (OFFSET_SHORT_ITEM + srcData.length);
            byte[] data = Arrays.copyOf(srcData, srcData.length + 1);
            System.arraycopy(data, 0, data, 1, srcData.length);
            data[0] = length;

            return data;
        } else {
            // length of length = BX
            // prefix = [BX, [length]]
            int tmpLength = srcData.length;
            byte byteNum = 0;
            while (tmpLength != 0) {
                ++byteNum;
                tmpLength = tmpLength >> 8;
            }
            byte[] lenBytes = new byte[byteNum];
            for (int i = 0; i < byteNum; ++i) {
                lenBytes[byteNum - 1 - i] = (byte) ((srcData.length >> (8 * i)) & 0xFF);
            }
            // first byte = F7 + bytes.length
            byte[] data = Arrays.copyOf(srcData, srcData.length + 1 + byteNum);
            System.arraycopy(data, 0, data, 1 + byteNum, srcData.length);
            data[0] = (byte) (OFFSET_LONG_ITEM + byteNum);
            System.arraycopy(lenBytes, 0, data, 1, lenBytes.length);

            return data;
        }
    }

    public static byte[] encodeBigInteger(BigInteger srcBigInteger) {
        if(srcBigInteger == BigInteger.ZERO)
            return encodeByte((byte)0);
        else
            return encodeElement(asUnsignedByteArray(srcBigInteger));
    }

    public static byte[] encodeList(byte[]... elements) {

        int totalLength = 0;
        for (int i = 0; i < elements.length; ++i) {
            totalLength += elements[i].length;
        }

        byte[] data;
        int copyPos = 0;
        if (totalLength < SIZE_THRESHOLD) {

            data = new byte[1 + totalLength];
            data[0] = (byte) (OFFSET_SHORT_LIST + totalLength);
            copyPos = 1;
        } else {
            // length of length = BX
            // prefix = [BX, [length]]
            int tmpLength = totalLength;
            byte byteNum = 0;
            while (tmpLength != 0) {
                ++byteNum;
                tmpLength = tmpLength >> 8;
            }
            tmpLength = totalLength;
            byte[] lenBytes = new byte[byteNum];
            for (int i = 0; i < byteNum; ++i) {
                lenBytes[byteNum - 1 - i] = (byte) ((tmpLength >> (8 * i)) & 0xFF);
            }
            // first byte = F7 + bytes.length
            data = new byte[1 + lenBytes.length + totalLength];
            data[0] = (byte) (OFFSET_LONG_LIST + byteNum);
            System.arraycopy(lenBytes, 0, data, 1, lenBytes.length);

            copyPos = lenBytes.length + 1;
        }
        for (int i = 0; i < elements.length; ++i) {
            System.arraycopy(elements[i], 0, data, copyPos, elements[i].length);
            copyPos += elements[i].length;
        }
        return data;
    }

    /**
     * Parse wire byte[] message into RLP elements
     *
     * @param msgData
     *            - raw RLP data
     * @return rlpList
     *            - outcome of recursive RLP structure
     */
    public static RLPList decode2(byte[] msgData) {
        RLPList rlpList = new RLPList();
        fullTraverse(msgData, 0, 0, msgData.length, 1, rlpList);
        return rlpList;
    }

    /**
     * Get exactly one message payload
     */
    private static void fullTraverse(byte[] msgData, int level, int startPos,
                                     int endPos, int levelToIndex, RLPList rlpList) {

        try {
            if (msgData == null || msgData.length == 0)
                return;
            int pos = startPos;

            while (pos < endPos) {
                // It's a listToTable with a payload more than 55 bytes
                // data[0] - 0xF7 = how many next bytes allocated
                // for the length of the listToTable
                if ((msgData[pos] & 0xFF) > OFFSET_LONG_LIST) {

                    byte lengthOfLength = (byte) (msgData[pos] - OFFSET_LONG_LIST);
                    int length = calcLength(lengthOfLength, msgData, pos);

                    byte[] rlpData = new byte[lengthOfLength + length + 1];
                    System.arraycopy(msgData, pos, rlpData, 0, lengthOfLength
                            + length + 1);

                    RLPList newLevelList = new RLPList();
                    newLevelList.setRLPData(rlpData);

                    fullTraverse(msgData, level + 1, pos + lengthOfLength + 1,
                            pos + lengthOfLength + length + 1, levelToIndex,
                            newLevelList);
                    rlpList.add(newLevelList);

                    pos += lengthOfLength + length + 1;
                    continue;
                }
                // It's a listToTable with a payload less than 55 bytes
                if ((msgData[pos] & 0xFF) >= OFFSET_SHORT_LIST
                        && (msgData[pos] & 0xFF) <= OFFSET_LONG_LIST) {

                    byte length = (byte) ((msgData[pos] & 0xFF) - OFFSET_SHORT_LIST);

                    byte[] rlpData = new byte[length + 1];
                    System.arraycopy(msgData, pos, rlpData, 0, length + 1);

                    RLPList newLevelList = new RLPList();
                    newLevelList.setRLPData(rlpData);

                    if (length > 0)
                        fullTraverse(msgData, level + 1, pos + 1, pos + length
                                + 1, levelToIndex, newLevelList);
                    rlpList.add(newLevelList);

                    pos += 1 + length;
                    continue;
                }
                // It's an item with a payload more than 55 bytes
                // data[0] - 0xB7 = how much next bytes allocated for
                // the length of the string
                if ((msgData[pos] & 0xFF) > OFFSET_LONG_ITEM
                        && (msgData[pos] & 0xFF) < OFFSET_SHORT_LIST) {

                    byte lengthOfLength = (byte) (msgData[pos] - OFFSET_LONG_ITEM);
                    int length = calcLength(lengthOfLength, msgData, pos);

                    // now we can parse an item for data[1]..data[length]
                    byte[] item = new byte[length];
                    System.arraycopy(msgData, pos + lengthOfLength + 1, item,
                            0, length);

                    byte[] rlpPrefix = new byte[lengthOfLength + 1];
                    System.arraycopy(msgData, pos, rlpPrefix, 0,
                            lengthOfLength + 1);

                    RLPItem rlpItem = new RLPItem(item);
                    rlpList.add(rlpItem);
                    pos += lengthOfLength + length + 1;

                    continue;
                }
                // It's an item less than 55 bytes long,
                // data[0] - 0x80 == length of the item
                if ((msgData[pos] & 0xFF) > OFFSET_SHORT_ITEM
                        && (msgData[pos] & 0xFF) <= OFFSET_LONG_ITEM) {

                    byte length = (byte) ((msgData[pos] & 0xFF) - OFFSET_SHORT_ITEM);

                    byte[] item = new byte[length];
                    System.arraycopy(msgData, pos + 1, item, 0, length);

                    byte[] rlpPrefix = new byte[2];
                    System.arraycopy(msgData, pos, rlpPrefix, 0, 2);

                    RLPItem rlpItem = new RLPItem(item);
                    rlpList.add(rlpItem);
                    pos += 1 + length;

                    continue;
                }
                // null item
                if ((msgData[pos] & 0xFF) == OFFSET_SHORT_ITEM) {
                    byte[] item = ByteUtil.EMPTY_BYTE_ARRAY;
                    RLPItem rlpItem = new RLPItem(item);
                    rlpList.add(rlpItem);
                    pos += 1;
                    continue;
                }
                // single byte item
                if ((msgData[pos] & 0xFF) < OFFSET_SHORT_ITEM) {

                    byte[] item = { (byte) (msgData[pos] & 0xFF) };

                    RLPItem rlpItem = new RLPItem(item);
                    rlpList.add(rlpItem);
                    pos += 1;
                    continue;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("RLP wrong encoding", e);
        }
    }

    private static int calcLength(int lengthOfLength, byte[] msgData, int pos) {
        byte pow = (byte) (lengthOfLength - 1);
        int length = 0;
        for (int i = 1; i <= lengthOfLength; ++i) {
            length += (msgData[pos + i] & 0xFF) << (8 * pow);
            pow--;
        }
        return length;
    }
}
