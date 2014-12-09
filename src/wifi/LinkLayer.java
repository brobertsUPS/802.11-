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
	
	private int slotChoice;										//For command and status
	private double beaconInterval;
	private int statusCode;
	
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
		senderBuf = new ArrayDeque<Packet>(4); 			//limited buffer size of 4
		receiverBuf = new ArrayBlockingQueue<Packet>(4); 
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
		
		if(senderBuf.size() == 4)							//Hit limit on buffer size
			return 0;
		else{
			senderBuf.push(packet); 						//have to fill senderBuf with the data from packet
			return len;
		}
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
		output.println("LinkLayer: Sending command " + cmd + " with value " + val);
		if(cmd == 0){
			output.println("-------------- Commands and Settings -----------------");
			output.println("Cmd #0: Display command options and current settings");
			output.println("Cmd #1: Set debug level.  Currently at 0 \n\tUse -1 for full debug output, 0 for no output");
			output.println("Cmd #2: Set slot selection method.  Currently random \n\tUse 0 for random slot selection, any other value to use maxCW");
			output.println("Cmd #3: Set beacon interval.  Currently at 3 seconds \n\tValue specifies seconds between the start of beacons; -1 disables");
//			output.println("Cmd #4: Set beep interval.  Currently at 1 secondsValue is interpreted as seconds between beeps; -1 disables");
			
			//print slot choice and beacon interval
			output.println("The current slot choice: "+slotChoice);
			output.println("The current beacon interval " + beaconInterval);
			
		}
		if(cmd == 1){
			//turn diagnostic on or off
			output.println("Diagnostic turned on");
			output.println("Diagnostic turned off");
		}
		if(cmd == 2){
			output.println("Random slot window");
			//random slot window
				//choose randomly between 0 and max collision window allowed
			
			output.println("Fixed slot window");
			//fixed
				//choose maximum collision window allowed
		}
		if(cmd == 3){
			if(val == -1){
				//stop beacons
				output.println("Beacons have been stopped");
			}else{
				//beacon time is val *1000
				output.println("Beacon time is " + val*1000);
			}
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