/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {
		
	/** lock variables */
	private boolean CS_BUSY = false;						// indicate to be in critical section (accessing a shared resource) 
	private boolean WANTS_TO_ENTER_CS = false;				// indicate to want to enter CS
	private List<Message> queueack; 						// queue for acknowledged messages
	private List<Message> mutexqueue;						// queue for storing process that are denied permission. We really don't need this for quorum-protocol
	
	private LamportClock clock;								// lamport clock
	private Node node;
	
	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;
		
		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}
	
	public void acquireLock() {
		CS_BUSY = true;
	}
	
	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {
		boolean updated=false;
		System.out.println(node.nodename + " wants to access CS");
		
		// clear the queueack before requesting for votes
		queueack.clear();
		// clear the mutexqueue
		mutexqueue.clear();
		// increment clock
		clock.increment();
		// adjust the clock on the message, by calling the setClock on the message
		message.setClock(clock.getClock());
		// wants to access resource - set the appropriate lock variable
		WANTS_TO_ENTER_CS=true;
		
		// start MutualExclusion algorithm
		
		// first, removeDuplicatePeersBeforeVoting. A peer can contain 2 replicas of a file. This peer will appear twice
		List<Message> peers=removeDuplicatePeersBeforeVoting();
		// multicast the message to activenodes (hint: use multicastMessage)
		this.multicastMessage(message, peers);
		// check that all replicas have replied (permission)
		boolean permission = areAllMessagesReturned(peers.size());
		if(permission) {
			// if yes, acquireLock
			acquireLock();
			// node.broadcastUpdatetoPeers
			node.broadcastUpdatetoPeers(updates);
			// clear the mutexqueue
			mutexqueue.clear();
			// return permission
			multicastReleaseLocks(node.activenodesforfile);
			
//			this.multicastReleaseLocks(node.activenodesforfile);
			
			updated=true;
		}
		
		
		
		return updated;
	}
	
	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {
		
		// iterate over the activenodes
		for(Message m: activenodes) {
			Util.getProcessStub(m.getNodeIP(), m.getPort()).onMutexRequestReceived(message);;
		}
		// obtain a stub for each node from the registry
			
		// call onMutexRequestReceived()
		
	}
	
	public void onMutexRequestReceived(Message message) throws RemoteException {
		
		// increment the local clock
		clock.increment();
		// if message is from self, acknowledge, and call onMutexAcknowledgementReceived()
			if(message.getNodeID().equals(node.getNodeID())) {
				message.setAcknowledged(true);
				node.onMutexAcknowledgementReceived(message);
			}

		int caseid = 0;
		// write if statement to transition to the correct caseid
		if(this.CS_BUSY) {
			caseid=1;
		}else if(this.WANTS_TO_ENTER_CS) {
			caseid=2;
		}
		
		// caseid=0: Receiver is not accessing shared resource and does not want to (send OK to sender)
		// caseid=1: Receiver already has access to the resource (dont reply but queue the request)
		// caseid=2: Receiver wants to access resource but is yet to - compare own message clock to received message's clock
		
		// check for decision
		doDecisionAlgorithm(message, mutexqueue, caseid);
	}
	
	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {
		
		String procName = message.getNodeIP();			// this is the same as nodeName in the Node class
		int port = message.getPort();					// port on which the registry for this stub is listening
		
		switch(condition) {
		
			/** case 1: Receiver is not accessing shared resource and does not want to (send OK to sender) */
			case 0: {
				// get a stub for the sender from the registry
				NodeInterface stub = Util.getProcessStub(procName, port);
				// acknowledge message
				message.setAcknowledged(true);
				// send acknowledgement back by calling onMutexAcknowledgementReceived()
				stub.onMutexAcknowledgementReceived(message);
				break;
			}
		
			/** case 2: Receiver already has access to the resource (dont reply but queue the request) */
			case 1: {
				mutexqueue.add(message);
					// queue this message
				
				break;
			}
			
			/**
			 *  case 3: Receiver wants to access resource but is yet to (compare own message clock to received message's clock
			 *  the message with lower timestamp wins) - send OK if received is lower. Queue message if received is higher
			 */
			case 2: {
				// check the clock of the sending process
				int messageClock = message.getClock();
				// own clock for the multicast message
				int localClock = clock.getClock();
				// compare clocks, the lowest wins
				if(messageClock==localClock) {
					if(message.getNodeID().compareTo(node.getNodeID())<0) {
						message.setAcknowledged(true);
						Util.getProcessStub(procName, port).onMutexAcknowledgementReceived(message);
					}else {
						mutexqueue.add(message);
					}
					
				}else if(messageClock<localClock) {
					message.setAcknowledged(true);
					Util.getProcessStub(procName, port).onMutexAcknowledgementReceived(message);
				}else {
					mutexqueue.add(message);
				}
				// if clocks are the same, compare nodeIDs, the lowest wins
				// if sender wins, acknowledge the message, obtain a stub and call onMutexAcknowledgementReceived()
				// if sender looses, queue it
				
				

				break;
			}
			
		}
		
	}
	
	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {
		// add message to queueack
		queueack.add(message);
		
	}
	
	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {
		
		// iterate over the activenodes
		
		// obtain a stub for each node from the registry
		
		// call releaseLocks()
		activenodes.forEach( a -> {
				try {
					Util.getProcessStub(a.getNodeIP(), a.getPort()).releaseLocks();
				} catch (RemoteException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} );
		
	
	}
	
	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
		// check if the size of the queueack is same as the numvoters
		boolean sameSize=false;
		if(queueack.size()==numvoters) {
			sameSize=true;
		}
		// clear the queueack
		queueack.clear();
		// return true if yes and false if no

		return sameSize;
	}
	
	private List<Message> removeDuplicatePeersBeforeVoting() {
		
		List<Message> uniquepeer = new ArrayList<Message>();
		for(Message p : node.activenodesforfile) {
			boolean found = false;
			for(Message p1 : uniquepeer) {
				if(p.getNodeIP().equals(p1.getNodeIP())) {
					found = true;
					break;
				}
			}
			if(!found)
				uniquepeer.add(p);
		}		
		return uniquepeer;
	}
}
