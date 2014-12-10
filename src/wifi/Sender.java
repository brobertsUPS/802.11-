package wifi;
import java.util.ArrayDeque;
import java.util.HashMap;

import rf.RF;

/**
 *
 * Threaded sender that continually checks the channel for idle time to send packets
 * @author Brandon Roberts
 * @author Nate Olderman
 *
 */
public class Sender implements Runnable{
	private static final int DIFS = RF.aSIFSTime + (2 * RF.aSlotTime);

	//IEEE spec says beacon interval should be 102,400 microseconds, this is in milliseconds
	private static final double BEACON_INTERVAL = 102.4; 
	private long lastBeaconTime;
	
	private long[] clockOffset;
	private short ourMAC;
	private RF rf;
	private ArrayDeque<Packet> senderBuf;
	private int backOffCount;
	private int windowSize;

	
	/**
	 * Makes a new Sender object that continually checks the channel for idle time to send packets
	 * @param theRF the RF layer to send packets out through
	 * @param senderBuffer the queue of packets needing to be sent
	 * @param ourMACAddr the MAC address
	 * @param currentClockOffset the offset from the time in rf.clock(), it is an array to be used as a pointer and shared with the reciever
	 */
	public Sender(RF theRF, ArrayDeque<Packet> senderBuffer, short ourMACAddr, long[] currentClockOffset){
		rf = theRF;
		senderBuf = senderBuffer;
		ourMAC = ourMACAddr;
		clockOffset = currentClockOffset;

		backOffCount = 0;
		windowSize = 1;
		lastBeaconTime = 0;
	}
	
	/**
	 * Waits to see if RF channel is idle, and sends when it has information
	 */
	public void run() {
		while(true)
			waitForFrame();
	}

	/**
	 * Waits for frame to become available
	 */
	private void waitForFrame(){
		//checkBeacon();

		if(!senderBuf.isEmpty()){
			if(senderBuf.peek().getFrameType() == 1){				// ths is an ACK we want to send
				waitForIdleChannelToACK();							// checks if channel is idle and then waits SIFS
				rf.transmit(senderBuf.peek().toBytes());			//transmit the ack
				senderBuf.remove();									//pull the ack message off that we want to send
			}else{													//not an ack we want to send
				if(!rf.inUse())
					waitIFS();
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
	 * Does an extended IFS wait because the channel was not initially idle
	 */
	private void backOffWaitIFS(){
		try {						//do initial DIFS
			Thread.sleep(DIFS);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting IFS");
		}

		if(backOffCount !=0){								//do a backoff if we havent counted down to 0
			backOffCount--;
			waitSlotTime();
		}else{												//finished backoff wait and got to 0
			if(rf.inUse()){									//If someone popped in right before us we have to wait again
				waitForIdleChannel();
			}
			else{											//no one on channel and we are clear to go
				rf.transmit(senderBuf.peek().toBytes());
				waitForAck();
			}
		}
	}

	/**
	 * Waits a slot time
	 */
	private void waitSlotTime(){
		try {
			Thread.sleep(RF.aSlotTime);						//do one slot time wait
		} catch (InterruptedException e) {
			System.err.println("Failed waiting IFS");
		}
		if(rf.inUse())										//channel is used and we can't continue doing our slot time wait
			waitForIdleChannel();
		else{
			if(backOffCount > 0){							//still haven't reached the end of backoff waiting
				backOffCount--;								
				waitSlotTime();		
			}
			else{											//go to the end of waiting and can transmit
				rf.transmit(senderBuf.peek().toBytes());
				waitForAck();
			}
		}
	}

	/**
	 * Waits the IFS time 
	 */
	private void waitIFS(){
		
		try {
			Thread.sleep(DIFS);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting IFS");
		}
		if(!rf.inUse()){								//still not in use and we are good to send
			rf.transmit(senderBuf.peek().toBytes());
			waitForAck();
		}else{ 											//someone got on the channel and we need to wait until they are done to resume
			waitForIdleChannel();
		}
	}
	
	/**
	 * Waits for the SIFS time
	 */
	private void waitSIFS(){
		try {
			Thread.sleep(RF.aSIFSTime);
		} catch (InterruptedException e) {
			System.err.println("Failed waiting IFS");
		}
		if(rf.inUse())
			waitForIdleChannelToACK();
	}
	
	/**
	 * Waits until the channel is available then waits SIFS
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
	 * Wait
	 * @throws InterruptedException 
	 */
	private void waitForAck(){										//Get here from wait IFS
		long startTime = rf.clock();

		while(!senderBuf.isEmpty()){								//make sure there is something on the buffer (could have pulled off in a previous iteration of the while)
			Packet topPacket = senderBuf.peek();

			//if it was a beacon, don't wait for an ack
			if(topPacket.getFrameType() == 2){
				senderBuf.remove(topPacket);
				break;
			}
			else if(topPacket.isAcked()){
				long timeoutVal = rf.clock() - startTime;
				
				System.out.println("The Packet has been acked");
				senderBuf.remove(topPacket);								//since it is acked we pull it off
				windowSize = 1; 									//resetting window size
				break;
			}

			if(topPacket.getNumRetryAttempts()  >= RF.dot11RetryLimit){  //hit retry limit and it breaks so that it will pull it off the buffer								
				System.out.println("Hit retry LIMIT");
				senderBuf.remove(topPacket);
				break;
			}

			if(rf.clock() - startTime >= 10000){ 	 					//if it has taken longer than a ten seconds, so timeout and retransmit
				System.out.println("SENDER got to timeout and now trying to retransmit");
				windowSize *=2; 										//double window size
				backOffCount = (int) (Math.random()*(windowSize + 1));  //give the option to roll a zero

				topPacket.retry();//increment the retry attempt counter in the packet

				//try to resend
				if(rf.inUse())
					waitForIdleChannel();
				else 
					backOffWaitIFS();
			} else{
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					System.err.println("Failed waiting for ACK");
				}
			}
		}
	}

	/**
	 * Returns when the channel becomes idle
	 */
	private void waitForIdleChannel(){
		while(rf.inUse()){
			try{
				Thread.sleep(10);
			}catch(InterruptedException e){
				System.err.println("Sender interrupted!");
			}
		}
		backOffWaitIFS();//channel is idle so we start doing our backoff
	}

	/**
	* Checks if the beacon should be sent
	* If it should, creates a beacon packet and puts on the senderBuffer
	*/
	private void checkBeacon(){
		//lastBeaconTime isn't used or updated with offset to make comparisons quicker, however it is added to the clock offset in the packet
		if(rf.clock() - lastBeaconTime >= BEACON_INTERVAL){
			lastBeaconTime = rf.clock();

			//make a data buffer with the current clock time
			long beaconTime = lastBeaconTime + clockOffset[0];
			byte[] beaconTimeArray = new byte[8];//8 bytes for the beacon time
			for(int i = beaconTimeArray.length - 1; i >= 0; i--){
				beaconTimeArray[i] = (byte)(beaconTime & 0xFF);
				beaconTime = beaconTime >>> 8;
			}

			Packet beaconPacket = new Packet((short)2, (short)0, (short)-1, ourMAC, beaconTimeArray);
			senderBuf.addFirst(beaconPacket);
		}
	}
}