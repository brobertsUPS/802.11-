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
	private RF rf;
	//private long sendTime; //currently not used
	private ArrayDeque<Packet> senderBuf;
	private int backOffCount;
	private int windowSize;
	
	private static final int DIFS = RF.aSIFSTime + (2* RF.aSlotTime);
	
	/**
	 * Makes a new Sender object that continually checks the channel for idle time to send packets
	 * @param theRF the RF layer to send packets out through
	 * @param senderBuffer the queue of packets needing to be sent
	 */
	public Sender(RF theRF, ArrayDeque<Packet> senderBuffer){
		rf = theRF;
		senderBuf = senderBuffer;
		backOffCount = 0;
		windowSize = 1;
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
		
		if(!senderBuf.isEmpty()){
			System.out.println("SenderBuf frame type "+senderBuf.peek().getFrameType());
			if(senderBuf.peek().getFrameType() == 1){				// ths is an ACK we want to send
				waitForIdleChannelToACK();							// checks if channel is idle and then waits SIFS
//				System.out.println("SenderBuf initial size: " + senderBuf.size());
				rf.transmit(senderBuf.peek().toBytes());			//transmit the ack
//				System.out.println("Sender transmitted an ack packet");
				senderBuf.remove();									//pull the ack message off that we want to send
//				System.out.println("SenderBuf size: " + senderBuf.size());
			}else{													//not an ack we want to send
				if(!rf.inUse())										//
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
			
			System.out.println("SENDER calling isAcked() : "+senderBuf.peek().isAcked());
			
			if(senderBuf.peek().isAcked()){
				System.out.println("The Packet has been acked");
				senderBuf.pop(); 									//since it is acked we pull it off
				windowSize = 1; 									//resetting window size
				break;
			}

			System.out.println(senderBuf.peek().retry()  + " " + RF.dot11RetryLimit);
			if(senderBuf.peek().retry()  >= RF.dot11RetryLimit){  //hit retry limit and it breaks so that it will pull it off the buffer								
				System.out.println("Hit retry LIMIT");
				senderBuf.pop();
				break;
			}
			
			try{
				Thread.sleep((long)1000);
			}
			catch(Exception e){
				
			}
			
			if(rf.clock()-startTime >= 10000){ 	 					//if it has taken longer than a ten seconds, so timeout and retransmit
				System.out.println("SENDER got to timeout and now trying to retransmit");
				windowSize *=2; 									//double window size
				backOffCount = (int) (Math.random()*(windowSize + 1)); //give the option to roll a zero

				if(rf.inUse())
					waitForIdleChannel();
				else 
					backOffWaitIFS();
			} else{
				try {
					Thread.sleep(5);
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
	
}