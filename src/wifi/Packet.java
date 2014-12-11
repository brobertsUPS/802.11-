package wifi;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A class to represent an 802.11~ frame.
 * @author Nate Olderman
 * @author Brandon Roberts
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
		frameType = (short) ((recvPacket[0] & 0xF0) >> 4);		//get out the FrameType and the Retry bit in one number
		retry = (short) (frameType % 2);						//if it is odd, then the retry bit (that was the least significant bit in this 4 bit number) was 1
		frameType >>= 1;										//shift over to get rid of retry bit
		
		//sequence number
		seqNum = (short) (((recvPacket[0] & 0xF) << 8 ) + (recvPacket[1] & 0xFFF)); //pull out sequence number//pull out sequence number ************Getting wrong value right now

		//destination bytes 
		destAddr = (short) (((recvPacket[2] & 0xFF) << 8) + (recvPacket[3] & 0xFF));
		
		//source bytes
		srcAddr = (short) (((recvPacket[4] & 0xFF) << 8) + (recvPacket[5] & 0xFF));
		
		//data bytes
		data = new byte[recvPacket.length - 10]; //10 is always the number of bytes in the packet besides the data
		System.arraycopy(recvPacket, 6, data, 0, data.length);
		

		//-------CHECKSUM WORK ------//
		//get the checksum according to the other host
		int pktLen = recvPacket.length;
		int checksumVal =  (((recvPacket[pktLen-4] & 0xFF) << 24) + ((recvPacket[pktLen-3] & 0xFF) << 16 ) +
				((recvPacket[pktLen-2] & 0xFF) << 8) + (recvPacket[pktLen-1] & 0xFF));
		
		//calculate the actual checksum from what we recieved
		checksum.reset();
		checksum.update(recvPacket, 0, recvPacket.length-4);
		int newChecksum = (int)(checksum.getValue() & 0xFFFFFFFF);

		//check if there is a difference in the checksums to see if it is corrupted
		if(checksumVal != newChecksum)
			corrupted = true;
	}
	
	/**
	 * Turns the Packet information (control, destAddr, srcAddr, buffer, and CRC) into an array of bytes
	 * FOR SENDING ONLY
	 * @return the byte array representing the frame to transmit
	 */
	public synchronized byte[] toBytes(){
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
		checksum.reset();
		checksum.update(buffer, 0, bufLen - 4);		//get the checksum for everything up to the CRC bytes
		
		int checksumVal = (int)(checksum.getValue() & 0xFFFFFFFF);		//long representation of the checksum 

		buffer[bufLen-4] = (byte) (checksumVal >>> 24);			//least significant byte		
		buffer[bufLen-3] = (byte) (checksumVal >>> 16);
		buffer[bufLen-2] = (byte) (checksumVal >>> 8);
		buffer[bufLen-1] = (byte) (checksumVal & 0xFF);			//end byte
		

		return buffer;
	}
	
	
	/**
	 * String representation of the Packet
	 * @return a string representation of this packet
	 */
	public synchronized String toString(){
		return ("FrameType: " + frameType + " | Retry: " + retry + " | Sequence Number: " + seqNum + " | Destination Address: " + destAddr + " | Source Address: " + srcAddr + " | Data: " + data);
	}
	

//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Getters --------------------------------------------------//
//---------------------------------------------------------------------------------------------------//
	
	/**
	 * Gets the data message back in byte form.
	 * @return the data as a byte array
	 */
	public synchronized byte[] getDataBuf(){
		return data;
	}
	
	/**
	 * Gets the ENTIRE packet in bytes
	 * @return the entire packet's data
	 */
	public synchronized byte[] getPacket(){
		return packet;
	}
	
	/**
	 * Gets the source address for this packet
	 * @return the srcAddr as a short
	 */
	public synchronized short getSrcAddr(){
		return srcAddr;
	}
	
	/**
	 * Gets the destination address for this packet
	 * @return the destAdr as a short
	 */
	public synchronized short getDestAddr(){
		return destAddr;
	}
	
	/**
	 * Gets if the Packet has been ACKed by the other host
	 * Sender checks so that it can remove the packet from the QUEUE
	 * @return true if this packet has been ACKed
	 */
	public synchronized boolean isAcked() {
		return isACKed;
	}

	/**
	 * Gets the type of packet this is
	 * @return 0 if Data, 1 if ACK, 2 if Beacon, 4 if CTS, 5 if RTS
	 */
	public synchronized short getFrameType(){
		return frameType;
	}

	/**
	* Gets the sequence number of the packet
	* @return the sequence number of the packet
	*/
	public synchronized short getSeqNum(){
		return seqNum;
	}

	/**
	* Gets whether or not the packet was corrupted
	* @return true if the packet was corrupted
	*/
	public synchronized boolean checkIfCorrupt(){
		return corrupted;
	}

	/**
	* Gets the number of sending retry attempts this packet has done
	* @return the number of retry attempts
	*/
	public synchronized int getNumRetryAttempts(){
		return retryAttempts;
	}
	

//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Setters --------------------------------------------------//
//---------------------------------------------------------------------------------------------------//


	/**
	 * Sets the retry bit and increments the number of times this packet has been resent
	 */
	public synchronized void retry(){
		retry = 1;
		retryAttempts++;
	}

	/**
	 * Sets this packet as being ACKed by the other host
	 * Receiver sets this packet as being ACKed once it receives the ACK for this packet
	 */
	public synchronized void setAsAcked(){
		isACKed = true;
	}
	
	/**
	 * Sets the sequence number of this packet
	 * @param sequenceNum - the sequence number to set this packet to
	 */
	public synchronized void setSeqNum(short sequenceNum){
		seqNum = sequenceNum;
	}

}