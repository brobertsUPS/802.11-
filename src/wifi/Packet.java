package wifi;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A class to represent an 802.11~ frame.
 * @author Nate Olderman
 * @author Brandon Roberts
 *
 */
public class Packet {
	
	//control information
	private short retry; //retry bit if the packet is being resent
	private short seqNum; //sequence number of the packet
	private short frameType;
	
	private short destAddr; //destination address for packet
	private short srcAddr; //source address for packet
	
	private byte[] data; //packet's data only, without frame
	private byte[] packet; //the packet in it's entirety
	
	private boolean isACKed; //if this packet has been ACKed
	
	private int retryAttempts;
	
	private CRC32 checksum;
	private boolean corrupted;

	
	/**
	 * Compiles the packet from the various information that makes up the packet
	 * @param frameType the type of frame to make
	 * @param destination the packet's destination address
	 * @param source the packet's source address
	 * @param data the actual data being sent
	 */
	public Packet(short typeOfFrame, short sequenceNum, short destination, short source, byte[] theData){
		frameType = typeOfFrame; //putting into control temporarily
		seqNum = sequenceNum;
		destAddr = destination;
		srcAddr = source;
		data = theData;

		retryAttempts = 0;
		checksum = new CRC32();
		corrupted = false;
	}

	/**
	 * Creates a Packet from a byte array
	 * @param the byte array received from the rf layer
	 */
	public Packet(byte[] recvPacket){
		checksum = new CRC32();
		corrupted = false;

		packet = recvPacket;

		//build individual pieces of control
		frameType = (short) ((recvPacket[0] & 0xF0) >> 4);//get out the FrameType and the Retry bit in one number
		retry = (short) (frameType % 2);//if it is odd, then the retry bit (that was the least significant bit in this 4 bit number) was 1
		frameType >>= 1;//shift over to get rid of retry bit
		seqNum = (short) (((recvPacket[0] & 0xF) << 8 ) + (recvPacket[1] & 0xFFF));//pull out sequence number//pull out sequence number ************Getting wrong value right now
		//(short) ((short)((recvPacket[0] << 8 ) + recvPacket[1]) & 0xFFF); //old code to pull out sequence number
//		System.out.println("SEQNUM IN PACKET " + seqNum);
		//destination bytes 
		destAddr = (short) (((recvPacket[2] & 0xFF) << 8) + (recvPacket[3] & 0xFF));
		
		//source bytes
		srcAddr = (short) (((recvPacket[4] & 0xFF) << 8) + (recvPacket[5] & 0xFF));
		
		//data bytes
		data = new byte[recvPacket.length - 10]; //10 is always the number of bytes in the packet besides the data
		System.arraycopy(recvPacket, 6, data, 0, data.length);
		
		int pktLen = recvPacket.length;
		
		int checksumVal =  (((recvPacket[pktLen-4] & 0xFF)<< 24) + ((recvPacket[pktLen-3] & 0xFF) << 16 ) +
				((recvPacket[pktLen-2] & 0xFF) << 8) + (recvPacket[pktLen-1] & 0xFF));
		
		System.out.println("Calculated Check Sum Received is: " + checksumVal);
//		System.out.println(Arrays.toString(packet));
		checksum.update(recvPacket, 0, recvPacket.length-4);			//get the checksum for the received packet
//		System.out.println("What the Sum Should be: " + checksum.getValue());
		if(checksumVal != checksum.getValue())							//if the checksum doesn't match up then the packet is corrupted
			corrupted = true;
	}
	
	/**
	 * Turns the Packet information (control, destAddr, srcAddr, buffer, and CRC) into an array of bytes
	 * FOR SENDING ONLY
	 * @return the byte array representing the frame to transmit
	 */
	public byte[] toBytes(){
		byte[] buffer = new byte[data.length + 10];
		int bufLen = buffer.length;

		//build control piece
		buffer[0] = (byte) (((frameType & 0xFF) << 1) + retry);
		buffer[0] = (byte) ((buffer[0] << 4) + (seqNum >>> 8  & 0xF));
		buffer[1] = (byte) (seqNum & 0xFF);
		
		//destination bytes
		buffer[2] = (byte) (destAddr >>> 8);
		buffer[3] = (byte) (destAddr & 0xFF);
		
		//source bytes
		buffer[4] = (byte) (srcAddr >>> 8);
		buffer[5] = (byte) (srcAddr & 0xFF);
		
		//data bytes
		int bufferPos = 6;
		for(int i = 0; i < data.length; i++) //do the entire data and not minus 1
			buffer[bufferPos++] = data[i];
		
		//CRC bytes filled
		checksum.update(buffer, 0, bufLen - 4);			//get the checksum for everything up to the CRC bytes
		
		int checksumVal = (int)(checksum.getValue() & 0xFFFFFFFF);			//long representation of the checksum 
		System.out.println("Check Sum Sent is: " + checksumVal);
		
		buffer[bufLen-4] = (byte) ((checksumVal ) >>> 24);				//least significant byte		
		buffer[bufLen-3] = (byte) ((checksumVal ) >>> 16);
		buffer[bufLen-2] = (byte) ((checksumVal ) >>> 8);
		buffer[bufLen-1] = (byte) (checksumVal & 0xFF);							//end byte
		
		System.out.println(Arrays.toString(buffer));
		return buffer;
	}
	
	/**
	 * Sets the retry bit and returns the number of times this packet has been retransmitted
	 */
	public int retry(){
		retry = 1;
		return retryAttempts++;
	}
	
	
	/**
	 * String representation of the Packet
	 */
	public String toString(){
		return ("FrameType " + frameType + " Retry " + retry + " Sequence Number " + seqNum + " | " + destAddr + " | " + srcAddr + " | " + data);
	}
	
	//---------------------------------------------------------------------------------------------------//
	//---------------------------------------- Getters & Setters ----------------------------------------//
	//---------------------------------------------------------------------------------------------------//
	/**
	 * Gives the data message back in byte form.
	 * @return the data as a byte array
	 */
	public byte[] getDataBuf(){
		return data;
	}
	
	/**
	 * Returns the ENTIRE packet's data
	 * @return the entire packet's data
	 */
	public byte[] getPacket(){
		return packet;
	}
	
	/**
	 * Gives the source address back in byte form.
	 * @return the srcAddr as a short
	 */
	public short getSrcAddr(){
		return srcAddr;
	}
	
	/**
	 * Gives the destination address back in byte form.
	 * @return the destAdr as a short
	 */
	public short getDestAddr(){
		return destAddr;
	}
	
	/**
	 * Determines if the Packet has been ACKed by the other host
	 * Sender checks so that it can remove the packet from the QUEUE
	 * @return true if this packet has been ACKed
	 */
	public boolean isAcked() {
		return isACKed;
	}
	
	/**
	 * Sets this packet as being ACKed by the other host
	 * Receiver sets this packet as being ACKed once it receives the ACK for this packet
	 */
	public void setAsAcked(){
		isACKed = true;
	}
	
	/**
	 * Gives back the type of the frame
	 * @return 0 if Data, 1 if ACK, 2 if Beacon, 4 if CTS, 5 if RTS
	 */
	public short getFrameType(){
		return frameType;
	}
	
	/**
	 * Changes the packet to an ACK
	 */
	public void makeIntoACK() {
		frameType = 1;//make it an ACK type
		
		//flip the destination and source
		short temp = destAddr;
		destAddr = srcAddr;
		srcAddr = temp;
	}
	
	public short getSeqNum(){
		return seqNum;
	}
	
	/**
	 * Set seqNum
	 */
	public void setSeqNum(short sequenceNum){
		seqNum = sequenceNum;
	}

	/**
	* Gets whether or not the packet was corrupted
	*/
	public boolean isCorrupt(){
		return corrupted;
	}

	/**
	* 
	*/
	public int getNumRetryAttempts(){
		return retryAttempts;
	}
}