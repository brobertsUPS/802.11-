package wifi;
import rf.RF;

import java.util.concurrent.*;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Use this layer as a starting point for your project code. See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 * @author Nate Olderman
 * @author Brandon Roberts
 *
 */
public class LinkLayer implements Dot11Interface {
	private RF theRF;
	private short ourMAC; 										//Our MAC address
	private PrintWriter output; 								//The output stream we'll write to

	private ArrayDeque<Packet> senderBuf; 						//the buffer for sending packets
	private ArrayBlockingQueue<Packet> receiverBuf; 			//the buffer for receiving packets
	
	private HashMap<Short, Integer> sendSeqNums;				//seqNums for what we send out. key is destinationAddr, value is seqNum
	
	private int slotChoice;
	
	
	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * @param ourMAC MAC address
	 * @param output Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output){
		output.println("LinkLayer: Constructor ran.");

		this.ourMAC = ourMAC;
		this.output = output;

		theRF = new RF(null, null);
		senderBuf = new ArrayDeque<Packet>(); //TEMPORARILY SET TO 10
		receiverBuf = new ArrayBlockingQueue<Packet>(10); //TEMPORARILY SET TO 10
		sendSeqNums = new HashMap<Short, Integer>();
		
		long[] clockOffset = {0};//starts the clock offset at 0
		Thread sender = new Thread(new Sender(theRF, senderBuf, ourMAC, clockOffset));
		Thread receiver = new Thread(new Receiver(theRF, senderBuf, receiverBuf, ourMAC, output, clockOffset));
		
		sender.start();
		receiver.start();
	}
	
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		Packet packet = new Packet((short)0, getNextSeqNum(dest, sendSeqNums), dest, ourMAC, data);
		
		output.println("LinkLayer: Sending " + len + " bytes to " + dest);
		senderBuf.push(packet); //have to fill senderBuf with the data from packet
		return len;
	}
	
	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	public int recv(Transmission t) {
		output.println("LinkLayer: Pretending to block on recv()");

		Packet packet;
		try{
			packet = receiverBuf.take();//receive the packet
			return prepareForLayerAbove(t, packet);
		} catch(InterruptedException e){
			System.err.println("Receiver interrupted!");
		}
		return -1;
	}
	
	/**
	 * Returns a current status code. See docs for full description.
	 */
	public int status() {
		output.println("LinkLayer: Faking a status() return value of 0");
		return 0;
	}
	
	/**
	 * Passes command info to your link layer. See docs for full description.
	 */
	public int command(int cmd, int val) {
		output.println("LinkLayer: Sending command "+cmd+" with value "+val);
		if(cmd == 0){
			
		}
		if(cmd == 1){
			
		}
		if(cmd == 2){
			
		}
		if(cmd == 3){
			
		}
		return 0;
	}
	
	/**
	 * Pushes the packet to the layer above
	 */
	private int prepareForLayerAbove(Transmission t, Packet packet){
		byte[] packetData = packet.getDataBuf();
		t.setBuf(packetData);
		t.setSourceAddr(packet.getSrcAddr());
		t.setDestAddr(ourMAC);

		return packetData.length;
	}
	
	/**
	 * If given sendSeqNums, updates the next valid seqNum to send from our machine
	 * If given recvSeqNums, updates the expected seqNum for the next received packet
	 * @param address the address we are sending to or receiving from
	 * @return the next expected seqNum
	 */
	private short getNextSeqNum(short address, HashMap<Short, Integer> seqNums){
		if(!seqNums.containsKey(address) || seqNums.get(address) == 4096)//if it doesn't contain the key, or the sequence number overflowed
			seqNums.put(address, 0);	//updated this destAddr seqNum
		else
			seqNums.put(address, seqNums.get(address)+1);	//updated this address seqNum
			
		return seqNums.get(address).shortValue();
	}
	
	private void printSettings(){
		output.println("");
	}
}