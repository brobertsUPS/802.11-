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
	private static final int MAX_MAC = (1 << 16) - 2; // -2 so we don't include the MAC address of all ones in this value
	private static final int SEQ_NUM_LIMIT = (1 << 12); //the sequence numbers should never hit 2^12
	private static final short MAX_DATA_LENGTH = 2038; //the specified max number of bytes of data able to be sent
	private static final int BUFFER_SIZE_LIMIT = 4; //the limit to the size of the buffer

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
		senderBuf = new ArrayDeque<Packet>(BUFFER_SIZE_LIMIT);
		receiverBuf = new ArrayBlockingQueue<Packet>(BUFFER_SIZE_LIMIT);
		sendSeqNums = new HashMap<Short, Integer>();
		
		//--initialize and start sender and receiver threads--//
		Thread sender = new Thread(new Sender(theRF, senderBuf, ourMAC, localClock, output, sendSeqNums));
		Thread receiver = new Thread(new Receiver(theRF, senderBuf, receiverBuf, ourMAC, localClock, output));
		sender.start();
		receiver.start();
		
		//--set any status codes that may have occurred, no debug is printed because user cannot turn on debug until after this--//
		localClock.setLastEvent(LocalClock.SUCCESS); //Initial value of 802_init is successful
		if(theRF == null)
			localClock.setLastEvent(LocalClock.RF_INIT_FAILED); //Attempt to initialize RF layer failed
		if(ourMAC > MAX_MAC || ourMAC < -1)
			localClock.setLastEvent(LocalClock.BAD_MAC_ADDRESS); //Illegal MAC address was specified

		output.println("Link Layer initialized with MAC address " + ourMAC +
				"\nSend command 0 to see a list of supported commands");
	}
	
	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send. See docs for full description.
	 * @param dest the destination mac address
	 * @param data the data to send
	 * @param len the length of the data to send
	 * @return the number of bytes sent, 0 if not sent
	 */
	public int send(short dest, byte[] data, int len) {
		Packet packet = new Packet((short)0, getNextSeqNum(dest), dest, ourMAC, data);
		
		//--Debug and status codes--//
		boolean debugOn = localClock.getDebugOn();
		if(debugOn){
			output.println("Attepmting to send packet: " + packet.toString() + " At Time: " + (localClock.getLocalTime()));
			output.println("Slot Count: " + localClock.getBackoffCount() + " Collision Window: " + localClock.getCollisionWindow());
		}
		if(dest > MAX_MAC || dest < -1){
			localClock.setLastEvent(LocalClock.BAD_MAC_ADDRESS);//ILLEGAL_MAC_ADDRESS
			if(debugOn)
				output.println("ILLEGAL MAC ADDRESS");
			return 0;
		}
		if(len > MAX_DATA_LENGTH || len < 0){
			localClock.setLastEvent(LocalClock.ILLEGAL_ARGUMENT);//ILLEGAL_ARGUMENT 	One or more arguments are invalid
			if(debugOn)
				output.println("ILLEGAL ARGUMENT");
			return 0;
		}
		if(data == null){	
			localClock.setLastEvent(LocalClock.BAD_ADDRESS);//BAD_ADDRESS 	Pointer to a buffer or address was NULL
			if(debugOn)
				output.println("BAD ADDRESS");
			return 0;
		}
		if(senderBuf.size() >= 4){	//Hit limit on buffer size
			localClock.setLastEvent(LocalClock.INSUFFICIENT_BUFFER_SPACE);//Outgoing transmission rejected due to insufficient buffer space

			if(debugOn)
				output.println("INSUFFICIENT_BUFFER_SPACE");
			return 0;
		}

		//--No issues detected, so sending the packet--//
		output.println("LinkLayer: Sending " + len + " bytes to " + dest);
		
		senderBuf.push(packet);//put the packet on the sender buffer
		return len;
	}
	
	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 * @param t the transmission to fill with the data received
	 * @return the number of bytes received, -1 if receive failed
	 */
	public int recv(Transmission t) {
		if(t == null){
			localClock.setLastEvent(LocalClock.ILLEGAL_ARGUMENT);//One or more arguments are invalid
			if(localClock.getDebugOn())
				output.println("ILLEGAL ARGUMENT");
			return -1;
		}
		
		Packet packet;
		try{
			packet = receiverBuf.take(); //receive the packet

			if(localClock.getDebugOn())
				output.println("Received packet: " + packet.toString() + " At Time: " +  (localClock.getLocalTime()));
			
			return prepareForLayerAbove(t, packet);
		} 
		catch(InterruptedException e){
			System.err.println("Receiver interrupted!");
		}

		//would get here if receive failed, make status an unspecified error
		localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);
		if(localClock.getDebugOn())
			output.println("RECEIVE FAILED");
		return -1;
	}
	
	/**
	 * Returns the current status code. See docs for full description.
	 * @return the number corresponding to the status code
	 */
	public int status() {
		if(localClock.getDebugOn())
			output.println("Current Status: " + localClock.getLastEvent());
		return localClock.getLastEvent();
	}
	
	/**
	 * Passes command info to your link layer. See docs for full description.
	 * @param cmd the command to do
	 * @param val the value for the command
	 * @return 0 on success, but this never fails
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
				output.println("Set as random slot window with collision window: " + localClock.getCollisionWindow());
			else
				output.println("Set as fixed slot window with collision window: " + localClock.getCollisionWindow());
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


//----------------------------------------------------------------------------------------------------------//
//---------------------------------------- Helper Methods --------------------------------------------------//
//----------------------------------------------------------------------------------------------------------//
	
	/**
	 * Pushes the packet to the layer above by transfering the data from packet to transmission
	 * @param t the transmission to fill from the data in the packet
	 * @param packet the packet whose data will be used to fill transmission
	 * @return the length of the data
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
	private short getNextSeqNum(short address){
		if(!sendSeqNums.containsKey(address) || sendSeqNums.get(address) >= SEQ_NUM_LIMIT)//if it doesn't contain the key, or the sequence number overflowed
			sendSeqNums.put(address, 0);
		else
			sendSeqNums.put(address, sendSeqNums.get(address) + 1); //update this address seqNum
			
		return sendSeqNums.get(address).shortValue();
	}
}