package wifi;

import rf.RF;

import java.util.concurrent.*;
import java.io.PrintWriter;
import java.util.*;

/**
 * Use this layer as a starting point for your project code. See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 * @author Nate Olderman
 * @author Brandon Roberts 
 */
public class LinkLayer implements Dot11Interface {
	private RF theRF;
	private short ourMAC; 										//Our MAC address
	private PrintWriter output; 								//The output stream we'll write to

	private ArrayDeque<Packet> senderBuf; 						//the buffer for sending packets
	private ArrayBlockingQueue<Packet> receiverBuf; 			//the buffer for receiving packets
	
	private HashMap<Short, Integer> sendSeqNums;				//seqNums for what we send out. key is destinationAddr, value is seqNum
	private LocalClock localClock;

	private int statusCode;
	
	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * @param ourMAC MAC address
	 * @param output Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output){
		
		
		
		this.ourMAC = ourMAC;
		this.output = output;

		theRF = new RF(output, null);
		localClock = new LocalClock(theRF);
				
		senderBuf = new ArrayDeque<Packet>(4); 			//limited buffer size of 4
		receiverBuf = new ArrayBlockingQueue<Packet>(4); 
		
		if(senderBuf.size() <0 || receiverBuf.size() <0)
			localClock.setLastEvent(6);//BAD_BUF_SIZE 	Buffer size was negative
			
		sendSeqNums = new HashMap<Short, Integer>();

		
		
		if(ourMAC > 65534 || ourMAC < -1)//largest viable mac is 65534
			localClock.setLastEvent(8);//BAD_MAC_ADDRESS 	Illegal MAC address was specified
		
		localClock.setLastEvent(1); //SUCCESS 	Initial value if 802_init is successful
		if(theRF == null)
			localClock.setLastEvent(3); //RF_INIT_FAILED 	Attempt to initialize RF layer failed
		
		Thread sender = new Thread(new Sender(theRF, senderBuf, ourMAC, localClock, output));
		Thread receiver = new Thread(new Receiver(theRF, senderBuf, receiverBuf, ourMAC, localClock, output));
		
		sender.start();
		receiver.start();
		
		output.println("Link Layer initialized with MAC address " + ourMAC +
				"\nSend command 0 to see a list of supported commands");
	}
	
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		boolean debugOn = localClock.getDebugOn();
			
		Packet packet = new Packet((short)0, getNextSeqNum(dest, sendSeqNums), dest, ourMAC, data);
		if(debugOn){
			output.println("Attepmting to send packet: " + packet.toString() + " At Time: " + (localClock.getLocalTime()));
			output.println("Slot Count: " + localClock.getBackoffCount() + " Collision Window: " + localClock.getCollisionWindow());
		}
		
		if(dest > 65534)
			localClock.setLastEvent(8);//ILLEGAL_MAC_ADDRESS
		
		if(data == null || len >2038){
			localClock.setLastEvent(9);//ILLEGAL_ARGUMENT 	One or more arguments are invalid
			if(debugOn)
				output.println("ILLEGAL_ARGUMENT");
			}
		
		output.println("LinkLayer: Sending " + len + " bytes to " + dest);
		
		if(senderBuf.size() == 4){	//Hit limit on buffer size
			localClock.setLastEvent(10);//INSUFFICIENT_BUFFER_SPACE 	Outgoing transmission rejected due to insufficient buffer space
			if(debugOn)
				output.println("INSUFFICIENT_BUFFER_SPACE");
			return 0;
			}
		else{
			senderBuf.push(packet); //have to fill senderBuf with the data from packet
			return len;
		}
	}
	
	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	public int recv(Transmission t) {

		if(t == null){
			localClock.setLastEvent(9);// ILLEGAL_ARGUMENT 	One or more arguments are invalid
			if(localClock.getDebugOn())
				output.println("ILLEGAL_ARGUMENT");
		}
		
		Packet packet;
		try{
			packet = receiverBuf.take();//receive the packet
			if(localClock.getDebugOn())
				output.println("Received packet: " + packet.toString() + " At Time: " +  (localClock.getLocalTime()));
			return prepareForLayerAbove(t, packet);
		} catch(InterruptedException e){
			System.err.println("Receiver interrupted!");
		}
		return -1;
	}
	
	/**
	 * Returns the current status code. See docs for full description.
	 */
	public int status() {
		if(localClock.getDebugOn())
			output.println("Current Status: " + localClock.getLastEvent());
		return localClock.getLastEvent();
	}
	
	/**
	 * Passes command info to your link layer. See docs for full description.
	 */
	public int command(int cmd, int val) {
		output.println("LinkLayer: Sending command " + cmd + " with value " + val);
		
		if(cmd == 0){
			output.println("-------------- Commands and Settings -----------------");
			output.println("Cmd #0: Display command options and current settings");
			output.println("Cmd #1: Set debug level.  Debug is on: " + localClock.getDebugOn() + " \n\tUse -1 for full debug output, 0 for no output");
			output.println("Cmd #2: Set slot selection method.  Currently fixed: " +localClock.getSlotSelectionFixed()+ ", with window of "+ localClock.getCollisionWindow()+ "\n\tUse 0 for random slot selection, any other value to use maxCW");
			output.println("Cmd #3: Set beacon interval.  Currently at "+ localClock.getBeaconInterval()/1000 + " seconds \n\tValue specifies seconds between the start of beacons; -1 disables");

			return 0;
		}
		else if(cmd == 1){	//turn diagnostic on or off
			localClock.setDebug(val);
			if(val == 0){
				output.println("Diagnostic turned off");
			}else{
				output.println("Diagnostic turned on");	
				output.println("The current slot choice is fixed: " + localClock.getSlotSelectionFixed() + 
								"\n\t The current beacon interval: " + localClock.getBeaconInterval() + 
								"\n\t Beacons are turned on: " + localClock.getBeaconsOn() + 
								"\n\t Debug is on: " + localClock.getDebugOn() +
								"\n\t Current BackoffCount: " + localClock.getBackoffCount() + 
								"\n\t Collsion window: " + localClock.getCollisionWindow() + 
								"\n\t Last event status: " + localClock.getLastEvent());
			}
		}
		else if(cmd == 2){	//Set slot selection to fixed or random
			localClock.setSlotSelectionFixed(val);
			
			if(val == 0)//random slot window
				output.println("Random slot window with collision window: " + localClock.getCollisionWindow());
			else
				output.println("Fixed slot window with collision window: " + localClock.getCollisionWindow());
		}
		else if(cmd == 3){	//turn beacon off or set it to a specified number of seconds
			localClock.setBeaconInterval(val);
			if(val == -1)
				output.println("Beacons have been stopped");
			else
				output.println("Beacons have been set to " + val + " seconds");
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
	
	public int getMAC(){
		return ourMAC;
	}
}