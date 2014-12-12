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
			localClock.setLastEvent(7);//BAD_ADDRESS 	Pointer to a buffer or address was NULL
			if(localClock.getDebugOn())
				output.println("BAD ADDRESS");
		}
		
		while(true){
			Packet packet = new Packet(rf.receive()); 

			//---all conditions below are mutually exclusive, if one happens, none of the others happen---//

			if(packet.checkIfCorrupt()){
				//--------ADD GENERAL ERROR HERE-------//
			}

			//if the packet is a beacon
			else if(packet.getFrameType() == 2 && packet.getDestAddr() == -1)
				localClock.updateClockOffset(packet);

			//if the buffer is full
			else if(receiverBuf.size() >= 4 ){
				localClock.setLastEvent(10);//INSUFFICIENT_BUFFER_SPACE 	Outgoing transmission rejected due to insufficient buffer space
				
				if(localClock.getDebugOn())
					output.println("INSUFFICIENT BUFFER SPACE");
			}

			//if it was sent to everyone (bcast)
			else if(packet.getDestAddr() == -1){
				try{ 
					receiverBuf.put(packet);	//put up the broadcast no matter what
				} catch(InterruptedException e){
					System.err.println("Receiver interrupted!");
				}
			}

			//else if it's destination is our mac address
			else if(packet.getDestAddr() == ourMac){
				//if its an ack AND it has the same sequence number
				if((packet.getFrameType() == 1) && (packet.getSeqNum() == senderBuf.peek().getSeqNum()))
					senderBuf.peek().setAsAcked();		//tell sender that that packet was ACKed
			
				//not an ack so recieve the data
				else
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

		//if we haven't seen it yet
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

			recvSeqNums.put(packet.getSrcAddr(), (short)(expectedSeqNum + 1)); //update the expected sequence number
		
			senderBuf.addFirst(new Packet((short)1, packet.getSeqNum(), packet.getSrcAddr(), packet.getDestAddr(), new byte[1])); //make and add ACK to the senderBuf to be sent
			
			checkOutOfOrderTable(outOfOrderTable.get(packet.getSrcAddr())); //checks to see if there are other packets that had higher sequence numbers
		}
		
		//if the recieved packet has a higher sequence number than what we expect
		else if(expectedSeqNum < packet.getSeqNum()){ 
			output.println("Detected a gap in the sequence numbers on incoming data packets from host: " + packet.getSrcAddr());
			outOfOrderTable.get(packet.getSrcAddr()).add(packet.getSeqNum() - expectedSeqNum - 1, packet); //adding the packet to the spot in the arraylist corresponding to the distance from the expected sequence number -1 to maintain starting at 0
		}
	}

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
					outOfOrderTable.put(packets.get(i).getSrcAddr(), new ArrayList<Packet>(packets.subList(i+1, packets.size()))); //push up the items in the arrayList
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