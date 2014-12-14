package wifi;

import java.io.PrintWriter;
import java.util.*;

import rf.RF;

/**
 * Threaded sender that continually checks the channel for idle time to send packets
 * @author Brandon Roberts
 * @author Nate Olderman
 */
public class Sender implements Runnable{
	private static final int BUFFER_SIZE_LIMIT = 4; //the limit to the size of the buffers

	private RF rf;
	private LocalClock localClock;
	private short ourMAC;
	
	private HashMap<Short, Integer> sendSeqNums; //Key of the destAddress, and value of the next seqNum we are sending

	private ArrayDeque<Packet> senderBuf;

	private Packet currentPacket;	//keep track of the current packet that is being sent

	private PrintWriter output;		//output given by linkLayer


	/**
	 * Makes a new Sender object that continually checks the channel for idle time to send packets
	 * @param theRF the RF layer to send packets out through
	 * @param senderBuffer the queue of packets needing to be sent
	 * @param ourMACAddr the MAC address
	 * @param theLocalClock the local clock object
	 * @param theOutput the printwriter to write to 
	 */
	public Sender(RF theRF, ArrayDeque<Packet> senderBuffer, short ourMACAddr, LocalClock theLocalClock, PrintWriter theOutput, HashMap<Short, Integer> seqNums){

		rf = theRF;
		sendSeqNums = seqNums;
		senderBuf = senderBuffer;
		ourMAC = ourMACAddr;
		localClock = theLocalClock;

		currentPacket = null;

		output = theOutput;
	}

	/**
	 * Continually loops forever waiting for a new frame then trying to send it
	 */
	public void run() {
		if(senderBuf == null)
			localClock.setLastEvent(LocalClock.BAD_ADDRESS);//Pointer to a buffer or address was NULL
		//no debug print here because user cannot turn on debug until after this
		
		while(true)
			waitForFrame();
	}

	//---------------------------------------------------------------------------------------------------------//
	//---------------------------------------- Sender States --------------------------------------------------//
	//---------------------------------------------------------------------------------------------------------//

	/**
	 * State that waits for a frame
	 */
	private void waitForFrame(){
		if(localClock.getBeaconsOn())					//only send beacons if we have them turned on
			checkToSendBeacon();

		if(!senderBuf.isEmpty()){
			currentPacket = senderBuf.peek();

			if(currentPacket.getFrameType() == 1){ 		// if this is an ACK we want to send
				
				waitForIdleChannelToACK(); 				// checks if channel is idle and then waits SIFS
				rf.transmit(currentPacket.toBytes());	// transmit the ack
				senderBuf.remove(currentPacket); 		// pull the ack message off that we want to send
				
			}else{ 										// else this is not an ACK
				if(!rf.inUse())
					waitDIFS();
				else
					waitForIdleChannel();
			}
		}
		else{											//if the senderbuf is empty we wait for something to send
			try{
				Thread.sleep(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
	}

	/**
	 * State that first waits DIFS then checks for backoff
	 */
	private void backoffWaitIFS(){
		
		if(localClock.getDebugOn())
			output.println("Backoff waiting DIFS At Time: " +  (localClock.getLocalTime()));

		try {
			Thread.sleep(localClock.roundedUpDIFS());
		} catch (InterruptedException e) {
			System.err.println("Failed waiting DIFS");
		}

		int backoffCount = localClock.getBackoffCount();
		
		if(backoffCount != 0){ 							//do a backoff if we havent counted down to 0
			localClock.setBackoffCount(backoffCount-1);
			waitSlotTime();
		} else{											//finished backoff wait and got to 0
			
			if(rf.inUse())								//if someone popped in right before us we have to wait again
				waitForIdleChannel();
			else 										//no one on channel and we are clear to go
				transmitPacket();
		}
	}

	/**
	 * State that waits a slot time
	 */
	private void waitSlotTime(){
		if(localClock.getDebugOn())
			output.println("Waiting Slot time at Time: " +  (localClock.getLocalTime()));

		try {
			Thread.sleep(RF.aSlotTime);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting slot time");
		}
		
		if(rf.inUse())										//channel is used and we can't continue doing our slot time wait
			waitForIdleChannel();
		else{
			int backoffCount = localClock.getBackoffCount();
			
			if(backoffCount > 0){							//still haven't reached the end of backoff waiting
				localClock.setBackoffCount(backoffCount -1);								
				waitSlotTime();		
			}
			else											//go to the end of waiting and can transmit
				transmitPacket();
		}
	}


	/**
	 * State that waits the DIFS time 
	 */
	private void waitDIFS(){
		if(localClock.getDebugOn())
			output.println("Waiting DIFS at Time: " +  (localClock.getLocalTime()));
		
		try {
			Thread.sleep(localClock.roundedUpDIFS());
		} catch (InterruptedException e) {
			System.err.println("Failed waiting DIFS");
		}

		if(!rf.inUse())										//still not in use and we are good to send
			transmitPacket();
		else												//someone got on the channel and we need to wait until they are done to resume
			waitForIdleChannel();
	}


	/**
	 * State that waits SIFS time
	 */
	private void waitSIFS(){
		
		if(localClock.getDebugOn())
			output.println("Waiting SIFS At Time: " +  (localClock.getLocalTime()));
		
		try {
			Thread.sleep(RF.aSIFSTime);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting SIFS");
		}

		if(rf.inUse())										//if channel is in use wait for it to be idle for an ack
			waitForIdleChannelToACK();
	}

	/**
	 * State that waits for the the channel to be idle in order to send an ACK
	 */
	private void waitForIdleChannelToACK(){
		if(localClock.getDebugOn())
			output.println("Waiting for idle channel to ack at Time: " +  (localClock.getLocalTime()));
		
		while(rf.inUse()){
			try{
				Thread.sleep(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
		
		waitSIFS();											//Only wait SIFS when idle because we are sending an ACK
	}

	/**
	 * State that waits for an ACK
	 */
	private void waitForACK(){
		if(currentPacket == null){
			localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);
			if(localClock.getDebugOn())
				output.println("Packet was not created properly");
		}

		//if it was being sent to MAC address -1 (Bcast) or was a beacon, don't wait for an ack
		else if(currentPacket.getDestAddr() == -1 || currentPacket.getFrameType() == 2)
			senderBuf.remove(currentPacket);

		else if(currentPacket.isAcked()){
			localClock.setLastEvent(LocalClock.TX_DELIVERED);//TX_DELIVERED 	Last transmission was acknowledged

			if(localClock.getDebugOn())
				output.println("TX DELIVERED");

			senderBuf.remove(currentPacket); //since it is acked we pull it off
			localClock.setCollisionWindow(1); //reset window size
		}

		else if(currentPacket.getNumRetryAttempts()  >= RF.dot11RetryLimit){  //hit retry limit and it breaks so that it will pull it off the buffer
			localClock.setLastEvent(LocalClock.TX_FAILED); //TX_FAILED 	Last transmission was abandoned after unsuccessful delivery attempts
			if(localClock.getDebugOn())
				output.println("TX FAILED: Setting dead host expected sequence number to 0");
			
			//remove this packet
			senderBuf.remove(currentPacket);

			//set the collision window back to 1
			localClock.setCollisionWindow(1);


			//--reset everything we saved for this host--//
			sendSeqNums.put(currentPacket.getDestAddr(), 0); //reset the next seqNum for this address back to 0
			
			//go through the senderBuf and remove any packets that were going to be sent to this host
			Packet[] senderQueue = new Packet[BUFFER_SIZE_LIMIT];
			senderBuf.toArray(senderQueue);
			for(int i = 0; i < senderQueue.length && senderQueue[i] != null; i++){
				if(senderQueue[i].getDestAddr() == currentPacket.getDestAddr())
					senderBuf.remove(senderQueue[i]);
			}			
		}

		else if(localClock.checkACKTimeout()) //if it has taken longer than a ten seconds, so timeout and retransmit
			timedOut();

		else{ //else not timed out yet
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				System.err.println("Failed waiting for ACK");
			}
			waitForACK();
		}

	}


	/**
	 * State that waits for the channel to be idle
	 */
	private void waitForIdleChannel(){
		if(localClock.getDebugOn())
			output.println("Waiting for idle channel at Time: " +  (localClock.getLocalTime()));
		while(rf.inUse()){
			try{
				Thread.sleep(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
		
		backoffWaitIFS();//channel is idle so we start doing our backoff
	}


	//----------------------------------------------------------------------------------------------------------//
	//---------------------------------------- Helper Methods --------------------------------------------------//
	//----------------------------------------------------------------------------------------------------------//

	/**
	 * Transmits the packet and waits for an ACK
	 */
	private void transmitPacket(){
		rf.transmit(currentPacket.toBytes());
		localClock.startACKTimer();
		waitForACK();
	}

	/**
	 * Checks if the beacon should be sent
	 * If it should, creates a beacon packet and puts on the senderBuffer
	 */
	private void checkToSendBeacon(){
		byte[] beaconTime = localClock.calcBeaconTime();

		//beacontime will be null if the beacon interval has not passed and don't put it in the queue if the que is full
		if(beaconTime != null && senderBuf.size() < BUFFER_SIZE_LIMIT){
			//if there is something in the queue, and the first thing is a beacon, just replace that beacon
			if(!senderBuf.isEmpty() && senderBuf.peek().getFrameType() == 2)
				senderBuf.pop();
			

			senderBuf.addFirst(new Packet((short)2, getNextSeqNum((short)-1), (short)-1, ourMAC, beaconTime));
		}
	}

	/**
	* Gets the next sequence number for the given destination address
	* @param destAddress the destination address to find the corresponding next sequence number 
	* @return the expected sequence number
	*/
	private short getNextSeqNum(short destAddress){
		//check whether or not we have sent to this address before
		if(!sendSeqNums.containsKey(destAddress)){
			sendSeqNums.put(destAddress, 0); //assuming it starts at zero
			return 0;
		}
		else
			return sendSeqNums.get(destAddress).shortValue(); //getting what sequence number we expect
	}

	/**
	 * Deals with the occassion where we timed out while waiting for an ack
	 */
	private void timedOut(){
		output.println("SENDER got to timeout and now trying to retransmit");
		
		localClock.setCollisionWindow(localClock.getCollisionWindow() * 2);//windowSize *= 2; double window size

		if(localClock.getDebugOn()){
			output.println("SENDER got to timeout and now trying to retransmit");
			output.println("Collision window changed to: " + localClock.getCollisionWindow());
		}

		//get the backoff count based on if the slot selection is fixed
		if(localClock.getSlotSelectionFixed()) 
			localClock.setBackoffCount(localClock.getCollisionWindow());//backoffCount = windowSize;
		else
			localClock.setBackoffCount((int)(Math.random()* (localClock.getCollisionWindow()+1)));//backoffCount = (int) (Math.random()*(windowSize + 1));

		if(localClock.getDebugOn())										//print if debug is on
			output.println("BackoffCount changed to: "+ localClock.getBackoffCount());

		currentPacket.retry(); 											//increment the retry attempt counter in the packet

		//try to resend
		if(rf.inUse())
			waitForIdleChannel();
		else 
			backoffWaitIFS();
	}
}