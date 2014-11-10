package wifi;

/**
 * A class to represent an 802.11~ frame.
 * @author Brandon Roberts
 *
 */
public class Packet {

	private short control;
	private short destAddr;
	private short srcAddr;
	private byte[] data;
	private boolean dataWaiting;
	private boolean retry;
	private short seqNum;
	
	/**
	 * Compiles the packet from the various information that makes up the packet
	 * @param frameType
	 * @param destination
	 * @param source
	 */
	public Packet(short frameType, short destination, short source, byte[] data){
		control = frameType; //putting into control temporarily
		destAddr = destination;
		srcAddr = source;
		this.data = data;
		buildControlBytes();
		
	}
	
	/**
	 * Creates a packet from a received packet
	 */
	public Packet(byte[] recveivedPacket){
		
	}
	
	/**
	 * Takes the long passed in and returns it as a byte array
	 * @return the byte representation of the long
	 */
	public byte[] toBytes(){
		byte[] buffer = new byte[data.length+10];
		
		//build control piece
		buffer[0] = (byte) ((control >>> 8) & 0xFF);   
		buffer[1] = (byte) (control & 0xFF);   
		
		//destination bytes
		buffer[2] = (byte) ((destAddr >>> 8) & 0xFF);  
		buffer[3] = (byte) (destAddr & 0xFF);   
		
		//source bytes
		buffer[4] = (byte) ((srcAddr >>> 8) & 0xFF);   
		buffer[5] = (byte) (srcAddr & 0xFF);   
		
		//data bytes
		int bufferPos = 6;
		for(int i=data.length-1; i >= 0; i--)
			buffer[bufferPos++] = data[i];
		
		for(int i=buffer.length-1; i>buffer.length-5;i--)
			buffer[i] = (byte)255;
		
		
		return buffer; 
	}
	
	/**
	 * Gives the data back in byte form.
	 * @return the data as a byte array
	 */
	public byte[] getDatabuf(){
		return data;
	}
	
	/**
	 * Gives the source address back in byte form.
	 * @return the srcAddr as a byte array
	 */
	public short getSourceAddr(){
		return srcAddr;
	}
	
	/**
	 * Turns the control instance into a byte array.
	 */
	private void buildControlBytes(){
		// 000 0 0000 00000000
		control <<= 1; 
		if(retry)
			control++;
		control <<= 12;
		control += (seqNum &0x1FF);		
	}
	
	/**
	 * 
	 */
	public String toString(){
		return "";
	}
}
