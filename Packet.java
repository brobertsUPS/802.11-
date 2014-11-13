import wifi.*;
/**
 * A class to represent an 802.11~ frame.
 * @author Nate Olderman
 * @author Brandon Roberts
 *
 */
public class Packet {

	private short control;		//control field, constructor initializes it with frame type
	private boolean retry;		//retry bit if the packet is being resent
	private short seqNum;		//sequence number of the packet

	private short destAddr;		//destination address for packet
	private short srcAddr;		//source address for packet
	private byte[] data;		//packet's data

	private boolean isAcked;	//defaults to false

	private byte[] packet;
	

	/**
	 * Compiles the packet from the various information that makes up the packet
	 * @param frameType		the type of frame to make
	 * @param destination	the packet's destination address
	 * @param source 		the packet's source address
	 * @param data 			the actual data being sent
	 */
	public Packet(short frameType, short destination, short source, byte[] data){
		control = frameType; //putting into control temporarily
		destAddr = destination;
		srcAddr = source;
		this.data = data;
		buildControlBytes();//fills the control variable with the correct bytes
	}
	

	/**
	 * Creates a Packet from a byte array
	 * @param the byte array received from the rf layer
	 */
	public Packet(byte[] recvPacket){
		packet = recvPacket;

		//build control piece
		control = (short) ((recvPacket[0] << 8 ) + recvPacket[1]);
		
		//destination bytes
		destAddr = (short) (((recvPacket[2] & 0xFF) << 8) + (recvPacket[3] & 0xFF));
		System.out.println(destAddr);
				
		//source bytes
		srcAddr = (short) (((recvPacket[4] & 0xFF) << 8) + (recvPacket[5] & 0xFF));
		
		//data bytes
		data = new byte[recvPacket.length - 10];
		System.arraycopy(recvPacket, 6, data, 0, data.length);

		//from when we reversed the data
		// data = new byte[recvPacket.length - 10];//create the data buffer with correct size
		// int bufferPosition = 6; 
		// for(int i = data.length-1; i >= 0; i--) //start taking from recvPacket at index 6 and put it at the end of the data buffer
		// 	data[i] = recvPacket[bufferPosition++];
	}
	

	/**
	 * Turns the Packet information (control, destAddr, srcAddr, buffer, and CRC) into an array of bytes
	 * FOR SENDING ONLY
	 * @return the byte array representing the frame to transmit
	 */
	public byte[] toBytes(){
		byte[] buffer = new byte[data.length+10];
		
		//build control piece
		buffer[0] = (byte) (control >>> 8);   
		buffer[1] = (byte) (control & 0xFF);   
		
		//destination bytes
		buffer[2] = (byte) (destAddr >>> 8);  
		buffer[3] = (byte) (destAddr & 0xFF);   
		
		//source bytes
		buffer[4] = (byte) (srcAddr >>> 8);   
		buffer[5] = (byte) (srcAddr & 0xFF);   
		
		//data bytes
		int bufferPos = 6;
		for(int i = 0; i < data.length-1; i++)
			buffer[bufferPos++] = data[i];

		//from when we reversed the data
		// int bufferPos = 6;
		// for(int i = data.length-1; i >= 0; i--)
		// 	buffer[bufferPos++] = data[i];
		
		//CRC bytes filled to all ones
		for(int i = buffer.length-1; i > buffer.length - 5; i--)
			buffer[i] = (byte) -1;
		
		return buffer; 
	}
	

	/**
	 * Correctly formats the control instance bytes for later transformation
	 */
	private void buildControlBytes(){
		control <<= 1; 
		if(retry)
			control++;
		control <<= 12;
		control += (seqNum &0x1FF);		
	}


	/**
	 * String representation of the Packet
	 */
	public String toString(){
		return (control + " | " + destAddr + " | " + srcAddr + " | " + data);
	}



//---------------------------------------------------------------------------------------------------//
//---------------------------------------- Getters & Setters ----------------------------------------//
//---------------------------------------------------------------------------------------------------//

	/**
	 * Gives the data message back in byte form.
	 * @return the data as a byte array
	 */
	public byte[] getDatabuf(){
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
	 * @return the srcAddr as a byte array
	 */
	public short getSourceAddr(){
		return srcAddr;
	}
	
	/**
	 * Determines if the Packet is an ACK
	 * @return true if this packet is an ACK
	 */
	public boolean isAcked() {
		return isAcked;
	}

	/**
	 * Marks the Packet as an ACK or not
	 * @param isAcked is true if the Packet is an ACK
	 */
	public void setAcked(boolean isAcked) {
		this.isAcked = isAcked;
	}
}
