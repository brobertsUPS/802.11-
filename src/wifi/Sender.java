package wifi;

import java.util.*;

import rf.RF;

/**
 * Threaded sender that continually checks the channel for idle time to send packets
 * @author Brandon Roberts
 * @author Nate Olderman
 */
public class Sender implements Runnable{
	private RF rf;
	private LocalClock localClock;
	private short ourMAC;
	
	private ArrayDeque<Packet> senderBuf;
	private int backoffCount;
	private int windowSize;

	private Packet currentPacket;	//keep track of the current packet that is being sent

	
	/**
	 * Makes a new Sender object that continually checks the channel for idle time to send packets
	 * @param theRF the RF layer to send packets out through
	 * @param senderBuffer the queue of packets needing to be sent
	 * @param ourMACAddr the MAC address
	 * @param theLocalClock the local clock object
	 */
	public Sender(RF theRF, ArrayDeque<Packet> senderBuffer, short ourMACAddr, LocalClock theLocalClock){
		rf = theRF;
		senderBuf = senderBuffer;
		ourMAC = ourMACAddr;
		localClock = theLocalClock;

		backoffCount = 0;
		windowSize = 1;
		currentPacket = null;
	}
	

	/**
	 * Continually loops forever waiting for a new frame then trying to send it
	 */
	public void run() {
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
		//checkBeacon();

		if(!senderBuf.isEmpty()){
			currentPacket = senderBuf.peek();

			if(currentPacket.getFrameType() == 1){					// if this is an ACK we want to send
				waitForIdleChannelToACK();							// checks if channel is idle and then waits SIFS
				rf.transmit(currentPacket.toBytes());				// transmit the ack
				senderBuf.remove(currentPacket);					// pull the ack message off that we want to send
			}else{													// else this is not an ACK
				if(!rf.inUse())
					waitDIFS();
				else
					waitForIdleChannel();
			}
		}
		else{
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
		try {
			Thread.sleep(localClock.roundedUpDIFS());
		} catch (InterruptedException e) {
			System.err.println("Failed waiting DIFS");
		}

		if(backoffCount != 0){								//do a backoff if we havent counted down to 0
			backoffCount--;
			waitSlotTime();
		}else{												//finished backoff wait and got to 0
			if(rf.inUse()){									//If someone popped in right before us we have to wait again
				waitForIdleChannel();
			}
			else	//no one on channel and we are clear to go
				transmitPacket();
		}
	}


	/**
	 * State that waits a slot time
	 */
	private void waitSlotTime(){
		try {
			Thread.sleep(RF.aSlotTime);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting slot time");
		}
		if(rf.inUse())										//channel is used and we can't continue doing our slot time wait
			waitForIdleChannel();
		else{
			if(backoffCount > 0){							//still haven't reached the end of backoff waiting
				backoffCount--;								
				waitSlotTime();		
			}
			else	//go to the end of waiting and can transmit
				transmitPacket();
		}
	}


	/**
	 * State that waits the DIFS time 
	 */
	private void waitDIFS(){
		try {
			Thread.sleep(localClock.roundedUpDIFS());
		} catch (InterruptedException e) {
			System.err.println("Failed waiting DIFS");
		}

		if(!rf.inUse())	//still not in use and we are good to send
			transmitPacket();
		else	//someone got on the channel and we need to wait until they are done to resume
			waitForIdleChannel();
	}
	

	/**
	 * State that waits SIFS time
	 */
	private void waitSIFS(){
		try {
			Thread.sleep(RF.aSIFSTime);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting SIFS");
		}

		if(rf.inUse())
			waitForIdleChannelToACK();
	}
	

	/**
	 * State that waits for the the channel to be idle in order to send an ACK
	 */
	private void waitForIdleChannelToACK(){
		while(rf.inUse()){
			try{
				Thread.sleep(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
		waitSIFS();
	}


	/**
	 * State that waits for an ACK
	 */
	private void waitForACK(){
		//if it was being sent to MAC address -1 (Bcast) or was a beacon, don't wait for an ack
		if(currentPacket.getDestAddr() == -1 || currentPacket.getFrameType() == 2)
			senderBuf.remove(currentPacket);
			
		else if(currentPacket.isAcked()){
			senderBuf.remove(currentPacket); //since it is acked we pull it off
			windowSize = 1; //reset window size
		}

		else if(currentPacket.getNumRetryAttempts()  >= RF.dot11RetryLimit){  //hit retry limit and it breaks so that it will pull it off the buffer								
			System.out.println("Hit retry LIMIT");
			senderBuf.remove(currentPacket);
			windowSize = 1;
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
	private void checkBeacon(){
		byte[] beaconTime = localClock.calcBeaconTime();

		//beacontime will be null if the beacon interval has not passed
		if(beaconTime != null)
			senderBuf.addFirst(new Packet((short)2, (short)0, (short)-1, ourMAC, beaconTime));
	}

	/**
	* Deals with the occassion where we timed out while waiting for an ack
	*/
	private void timedOut(){
		System.out.println("SENDER got to timeout and now trying to retransmit");

		windowSize *= 2; 	//double window size

		//get the backoff count based on if the slot selection is fixed
		if(localClock.getSlotSelectionFixed()) 
			backoffCount = windowSize;
		else
			backoffCount = (int) (Math.random()*(windowSize + 1));

		currentPacket.retry(); //increment the retry attempt counter in the packet

		//try to resend
		if(rf.inUse())
			waitForIdleChannel();
		else 
			backoffWaitIFS();
	}
}