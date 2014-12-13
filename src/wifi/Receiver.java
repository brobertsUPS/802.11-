package wifi;
import rf.RF;

import java.io.PrintWriter;
import java.util.concurrent.*;
import java.util.*;


/**
 * Threaded Receiver that continually watches the RF layer for incoming information
 * @author Brandon Roberts
 * @author Nate Olderman
 */
public class Receiver implements Runnable {
	private RF rf;
	private short ourMac;
	private LocalClock localClock;
	private PrintWriter output;

	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	
	private HashMap<Short, Short> recvSeqNums; //expected seqNum for stuff we get from other hosts
	private HashMap<Short, ArrayList<Packet>> outOfOrderTable; //packets that have a higher seqNum than we are expecting for the srcAddress
	

	/**
	 * Makes a new Receiver object that watches the RF layer for incoming information
	 * @param theRF the RF layer to receive from
	 * @param senderBuffer the queue of packets needing to be sent (use in Reciever to confirm ACK)
	 * @param receiverBuffer the que of received packets
	 * @param theMac our MAC address
	 * @param theLocalClock the local clock object
	 * @param outputWriter the output to write to
	 */
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer, short theMac, LocalClock theLocalClock, PrintWriter outputWriter){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
		ourMac = theMac;
		output = outputWriter;
		localClock = theLocalClock;

		recvSeqNums = new HashMap<Short, Short>();
		outOfOrderTable = new HashMap<Short, ArrayList<Packet>>();
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		
		if(receiverBuf == null){
			localClock.setLastEvent(LocalClock.BAD_ADDRESS);//BAD_ADDRESS 	Pointer to a buffer or address was NULL
			if(localClock.getDebugOn())
				output.println("BAD ADDRESS");
		}
		
		while(true){
			Packet packet = new Packet(rf.receive());
			
			//---all conditions below are mutually exclusive, if one happens, none of the others happen---//
			//if packet is corrupt
			if(packet.checkIfCorrupt()){
				localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);//UNSPECIFIED_ERROR 	General error code
				if(localClock.getDebugOn())
					output.println("UNSPECIFIED_ERROR");
			}

			//if the packet is a beacon (and we have beacons turned on)
			else if(packet.getFrameType() == 2 && packet.getDestAddr() == -1){
				if(localClock.getBeaconsOn())
					localClock.updateClockOffset(packet);
			}

			//if the buffer is full
			else if(receiverBuf.size() >= 4 ){
				localClock.setLastEvent(LocalClock.INSUFFICIENT_BUFFER_SPACE);//INSUFFICIENT_BUFFER_SPACE 	Outgoing transmission rejected due to insufficient buffer space
				
				if(localClock.getDebugOn())
					output.println("INSUFFICIENT BUFFER SPACE");
			}

			//if the packet was sent to everyone (bcast)
			else if(packet.getDestAddr() == -1){
				try{ 
					receiverBuf.put(packet);	//put up the broadcast no matter what
				} catch(InterruptedException e){
					System.err.println("Receiver interrupted!");
				}
			}

			//if the destination was our mac address
			else if(packet.getDestAddr() == ourMac){
				//if it's an ack AND it has the same sequence number
				if((packet.getFrameType() == 1) && (packet.getSeqNum() == senderBuf.peek().getSeqNum()))
					senderBuf.peek().setAsAcked();	//tell sender that that packet was ACKed

				else //not an ack so recieve the data
					checkSeqNum(packet);
			}
		}
	}


	/**
	* Checks the sequence number on the packet, and does any necessary sequence number work
	* @param packet the packet whose sequence number it is checking
	*/
	private void checkSeqNum(Packet packet){
		short expectedSeqNum = getExpectedSeqNum(packet.getSrcAddr()); 

		//if we haven't seen this host yet
		if(expectedSeqNum == 0)
			outOfOrderTable.put(packet.getSrcAddr(), new ArrayList<Packet>());
		
		//if the sequence number is what we expect
		if(expectedSeqNum == packet.getSeqNum()){
			//put it in the reciever buf to be taken by the layer above
			try{ 
				receiverBuf.put(packet);
			} catch(InterruptedException e){
				System.err.println("Receiver interrupted!");
			}

			//update the expected sequence number
			recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum + 1));
		
			//make an ACK and add to the front of the senderBuf
			senderBuf.addFirst(new Packet((short)1, packet.getSeqNum(), packet.getSrcAddr(), packet.getDestAddr(), new byte[1])); 
			
			//checks to see if there are other packets that had higher sequence numbers that should be pushed to layer above
			checkOutOfOrderTable(outOfOrderTable.get(packet.getSrcAddr()));
		}
		
		//if the recieved packet has a higher sequence number than what we expect
		else if(expectedSeqNum < packet.getSeqNum()){ 
			localClock.setLastEvent(LocalClock.UNSPECIFIED_ERROR);
			//doesn't print out error message if debug is on because we were supposed to print out the fact that a gap was detected whether or not debug was on
			output.println("Detected a gap in the sequence numbers on incoming data packets from host: " + packet.getSrcAddr());
			
			ArrayList<Packet> missingPackets = outOfOrderTable.get(packet.getSrcAddr());//get a pointer to make the next line readable

			//add the packet to the spot in the arraylist corresponding to the distance from the expected sequence number -1 to maintain starting at 0
			missingPackets.add(packet.getSeqNum() - expectedSeqNum - 1, packet); 
		}
	}

	/**
	* Gets the expected sequence number for the given source address
	* @param sourceAddress the source address to find the corresponding expected sequence number 
	* @return the expected sequence number
	*/
	private short getExpectedSeqNum(short sourceAddress){
		//check whether or not we have recieved from this address before
		if(!recvSeqNums.containsKey(sourceAddress)){
			recvSeqNums.put(sourceAddress, (short) 0); //assuming it starts at zero
			return 0;
		}
		else
			return recvSeqNums.get(sourceAddress).shortValue(); //getting what sequence number we expect
	}

	/**
	* Helper method that checks if there are packets with higher seqNums waiting in the queue that should be given to the layer above
	* @param packets the queue of packets with higher seq nums
	*/
	private void checkOutOfOrderTable(ArrayList<Packet> packets){
		if(!packets.isEmpty()){
			for(int i = 0; i < packets.size(); i++){ //go through everything in the queue
				if(packets.get(i) == null){//if this spot is a gap

					//advance items in the arraylist so the first packet in the queue is at index 0
					//doesn't leave a spot for the gap because that is what we are expecting next
					outOfOrderTable.put(packets.get(i).getSrcAddr(), new ArrayList<Packet>(packets.subList(i+1, packets.size())));
					
					recvSeqNums.put(packets.get(i).getSrcAddr(), (short)i);//update expected seqNum
					break;
				}

				//give it to the layer above
				try{ 
					receiverBuf.put(packets.get(i));
				} catch(InterruptedException e){
					System.err.println("Receiver interrupted!");
				}
			}
		}
	}
}