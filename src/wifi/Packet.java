package wifi;

/**
 * A class to represent an 802.11~ frame.
 * @author Brandon Roberts
 *
 */
public class Packet {

	private int control;
	private short destAddr;
	private short srcAddr;
	private byte[] data;
	private byte[] crc;
	private byte[] thePacket;
	private boolean dataWaiting;
	
	public Packet(int theControl, short destination, short source){
		control = theControl;
		destAddr = destination;
		srcAddr = source;
	}
	
	/**
	 * Takes the long passed in and returns it as a byte array
	 * @return the byte representation of the long
	 */
	private byte[] toBytes(){
		return data; 
	}
	
	/**
	 * Fills 
	 */
	public void fromBytes(){//What does this need to take in and what does it return?
		
	}
	
	/**
	 * Gives the data back in byte form.
	 * @return the data as a byte array
	 */
	public byte[] getDatabuf(){
		return data;
	}
	
	/**
	 * Gives the destination address back in byte form.
	 * @return the destAddr as a byte array
	 */
	public short getDestAddr(){
		return destAddr;
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
	public void buildControlBytes(){
		
	}
	
	/**
	 * Turns the destAddr instance into a byte array.
	 */
	public void buildDestAddrBytes(){
		
	}
	
	/**
	 * Turns the srcAddr instance into a byte array.
	 */
	public void buildSRCAddrBytes(){
		
	}
	
	/**
	 * Turns the data instance into a byte array
	 */
	public void buildDataBytes(){
		
	}
	
	/**
	 * Turns the last four bytes into 1s
	 */
	public void buildCRCBytes(){
		
	}
	
	/**
	 * 
	 */
	public String toString(){
		return "";
	}
}
