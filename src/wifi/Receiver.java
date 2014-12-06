package wifi;
import rf.RF;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.*;


/**
 *
 * Threaded Receiver that continually watches the RF layer for incoming information
 * @author Brandon Roberts
 * @author Nate Olderman
 *
 */
public class Receiver implements Runnable {
	private RF rf;
	//private long recvTime;//currently not used
	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	private short ourMac;
	private long[] clockOffset;
	
	private PrintWriter output;
	
	private HashMap<Short, Short> recvSeqNums; 				//expected seqNum for stuff we get from other hosts
	private HashMap<Short, ArrayList<Packet>> outOfOrderTable;	//packets that have a higher seqNum than we are expecting for the srcAddress
	
	/**
	 * Makes a new Receiver object that watches the RF layer for incoming information
	 * @param theRF the RF layer to receive from
	 * @param senderBuffer the queue of packets needing to be sent (use in Reciever to confirm ACK)
	 * @param receiverBuffer the que of received packets
	 * @param currentClockOffset the offset from the time in rf.clock(), it is an array to be used as a pointer and shared with the sender
	 */
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer, short theMac, PrintWriter outputWriter, long[] currentClockOffset){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
		ourMac = theMac;
		output = outputWriter;
		clockOffset = currentClockOffset;

		recvSeqNums = new HashMap<Short, Short>();
		outOfOrderTable = new HashMap<Short, ArrayList<Packet>>();
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		while(true){
			try {
				Packet packet = new Packet(rf.receive());

				//if the packet is a beacon
				if(packet.getFrameType() == 2){
					updateClockOffset(packet);
				}
				//else if it's destination is our mac address
				else if(packet.getDestAddr() == ourMac){

					if((packet.getFrameType() == 1)  &&  (packet.getSeqNum() == senderBuf.peek().getSeqNum())){//if its an ack AND it has the same sequence number
						senderBuf.peek().setAsAcked();		//tell sender that that packet was ACKed
					}
					else{//not an ack we received
						short expectedSeqNum;
						
						if(!recvSeqNums.containsKey(packet.getSrcAddr())){//if it hasn't received from this address before
							recvSeqNums.put(packet.getSrcAddr(), (short) 0); //assuming it starts at zero
							expectedSeqNum = 0;
							outOfOrderTable.put(packet.getSrcAddr(), new ArrayList<Packet>());
						}
						else{
							//System.out.println("WHAT WE PUT IN EXPECTED: " +recvSeqNums.get(packet.getSrcAddr()).shortValue());
							expectedSeqNum = recvSeqNums.get(packet.getSrcAddr()).shortValue(); //changing from when we update it below to when we check it on next loop through
							//System.out.println("WHAT NOW HAVE FOR EXPECTED: " +expectedSeqNum);
						}
							
	
						//System.out.println("EXPECTED SEQNUM IN RECEIVER" + expectedSeqNum);
						ArrayList<Packet> packets = outOfOrderTable.get(packet.getSrcAddr());
						if(expectedSeqNum == packet.getSeqNum()){
							receiverBuf.put(packet);		//if received successfully we need to put an ack in senderBuf
							
							//***************************************************************************************************************************
							packet.makeIntoACK();			//make an ack if it was the one we expected
							senderBuf.addFirst(packet);
							
							//****Make sure to use the packets destination address now that we have made the packet an ack and swapped the addresses already************
							recvSeqNums.put(packet.getDestAddr(), (short)(expectedSeqNum + 1));	//only update the seqNum if we got the right one
							
							//****************************************************************************************************************************
							//if there are things waiting
							if(!packets.isEmpty()){ //*************************sends us back to Packet packet = new Packet(rf.receive());
								for (Packet queuedPacket : packets) { //put everything on the recieverbuffer until you hit a gap
									if(queuedPacket == null)
										break;
									receiverBuf.put(packet);
									recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum + 1));	//also update expected if we had packets stored
								}
							}
						}
						else if(expectedSeqNum < packet.getSeqNum()){ 
							output.println("Detected a gap in the sequence nmbers on incoming data packets from host: " + packet.getSrcAddr());
							//packets.add(packet.getSeqNum() - expectedSeqNum - 1, packet);  				//**********************************THIS GETS DESTROYED AND WE NEVER KEPT THE PACKET IN THE TABLE
							outOfOrderTable.get(packet.getSeqNum()).add(packet.getSeqNum() - expectedSeqNum - 1, packet);//adding the packet to the spot in the arraylist corresponding to the distance from the expected sequence number -1 to maintain starting at 0
						}
						//don't put it on the buffer if the received sequence number is less than the expected because we already got it
						
						//recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum+1));
						
						//change the packet into an ACK and just send it back out to the other host
						//packet.makeIntoACK();
						//senderBuf.addFirst(packet);
					}
					
				}				
			} catch (InterruptedException e) {
				System.err.println("Receiver interrupted!");
			}
		}
	}

	private void updateClockOffset(Packet packet){
		byte[] beaconTimeArray = packet.getDataBuf();
		long beaconTime = beaconTimeArray[beaconTimeArray.length-1];
			
		for(int i = beaconTimeArray.length - 2; i >= 0; i--){
			beaconTime = beaconTime << 8;
			beaconTime += beaconTimeArray[i];
		}

		long clockDifference = beaconTime - (clockOffset[0] + rf.clock());
		if(clockDifference > 0)
			clockOffset[0] += clockDifference;
	}
}