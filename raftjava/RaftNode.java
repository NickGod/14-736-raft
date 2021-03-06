
import lib.*;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


public class RaftNode implements MessageHandling {
    private int id;
    private int leaderID;
    private static TransportLib lib;
    private int port;
    private int num_peers;
    private Types type;

    private LogEntries lastEntry;
    
    private long electionTimeout;
    private int electionTimeoutMults = 25;

    private long heartbeatMillis = 150;
    private long heartbeatTimeout;

    private PersistentState state;
    private static final Random random = new Random();

    // base election timeout
    private static final long T = 250;  // 250ms

    private int commitIndex = 0; // index of highest log entry known to be committed
    private int lastApplied = 0; //  index of highest log entry applied to state machine
    private int firstIndexOfTerm = 0;

    private ArrayList<Integer> nextIndex; // for each server, index of the next log entry to sent to that server
    private ArrayList<Integer> matchIndex; //  for each server, index of highest log entry known to be replicated on server


    private synchronized void resetElectionTimeout() {
        // electionTimeout: 200 to 450ms
        electionTimeout = System.currentTimeMillis() + T + random.nextInt(electionTimeoutMults)*10;
    }

    private synchronized void resetHeartbeatTimeout() {
        heartbeatTimeout = System.currentTimeMillis() + heartbeatMillis;
    }

    public int getPort() { return this.port; }
    public synchronized void setLeaderId(int id) { this.leaderID = id; }
    public synchronized int getLeaderId() { return this.leaderID; }
    public synchronized long getCurrentElectionTimeout() { return this.electionTimeout; }
    public synchronized long getCurrentHeartbeatTimeout() { return this.heartbeatTimeout; }
    public synchronized Types getType() { return this.type; }
    public synchronized int getCommitIndex() { return commitIndex; }

    public RaftNode(int port, int id, int num_peers) throws Exception {
        this.id = id;
        this.port = port;
        this.num_peers = num_peers;
        this.leaderID = -1;

        // start as follower
        this.type = Types.FOLLOWER;

        lib = new TransportLib(port, id, this);

        this.state = new PersistentState();

        nextIndex = new ArrayList<>();
        matchIndex = new ArrayList<>();
        try {
            // set election timeout before launching periodic tasks
            resetElectionTimeout();
            resetHeartbeatTimeout();
            launchPeriodicTasksThread();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // System.err.println("\nRaftNode created with id: " + id + " starting at port " + port + "\n");
    }


    // respond to vote request from peers
    public synchronized RequestVoteReply requestVote(RequestVoteArgs requestVoteArgs) {

        boolean voted = false;

        // a valid vote should have the same term (updated above)
        // should have votedFor set for this candidate or unset
        // should have more updated last log index
        // larger term wins
        // if terms are equal, larger index wins

        if(requestVoteArgs.getTerm() >= this.state.getCurrentTerm() && (state.getVotedFor() == -1
                || state.getVotedFor() == requestVoteArgs.getCandidateId())
                &&  (requestVoteArgs.getLastLogTerm() > this.state.getLog().lastEntryTerm() ||
                     (requestVoteArgs.getLastLogTerm() == this.state.getLog().lastEntryTerm()
                     && requestVoteArgs.getLastLogIndex() >= this.state.getLog().lastEntryIndex()))) {

            // set current as follower since others have higher term
            if (requestVoteArgs.getTerm() > this.state.getCurrentTerm())
                this.toFollower(requestVoteArgs.getTerm(), requestVoteArgs.getCandidateId());

            voted = true;
            this.state.setVotedFor(requestVoteArgs.getCandidateId());
            resetElectionTimeout();

        } else {
            // DEBUG LOG
            // if (state.getVotedFor() != -1 && state.getVotedFor() != requestVoteArgs.getCandidateId())
            //     System.err.println("Already voted for " + state.getVotedFor());

            // if (requestVoteArgs.getTerm() < this.state.getCurrentTerm())
            //     System.err.println("Node " + id + " term is higher: " + this.state.getCurrentTerm() +
            //         " candidate: " + requestVoteArgs.getCandidateId() + " term: " + requestVoteArgs.getTerm());

            // if (requestVoteArgs.getLastLogTerm() < this.state.getLog().lastEntryTerm() || 
            //     requestVoteArgs.getLastLogIndex() < this.state.getLog().lastEntryIndex())
            //     System.err.println("Node " + id + " last entry term: " + this.state.getLog().lastEntryTerm() +
            //         " last index: " + this.state.getLog().lastEntryIndex() + " candidate: " + requestVoteArgs.getCandidateId() 
            //         + " term: " + requestVoteArgs.getLastLogTerm() + " last index: " + requestVoteArgs.getLastLogIndex());
            // System.err.println("vote False for candidate: " + requestVoteArgs.getCandidateId());
        }


        return new RequestVoteReply(state.getCurrentTerm(), voted);
    }


    // handle append entries request
    // reply to hearbeats and log entries
    public synchronized AppendEntriesReply AppendEntries(AppendEntriesArg appendEntriesArg) {

        // has stale term
        if(appendEntriesArg.getTerm() < state.getCurrentTerm()) {
            return new AppendEntriesReply(state.getCurrentTerm(), false);
        }

        // transfer to follower status if the current node is waiting for election result
        // if already follower, that's ok, still update the leader and current term
        // also update the current leader

        if ((appendEntriesArg.getTerm() > state.getCurrentTerm()) || 
            (getType() == Types.CANDIDATE && appendEntriesArg.getTerm() == state.getCurrentTerm())) {
            this.toFollower(appendEntriesArg.getTerm(), appendEntriesArg.getLeaderId());
        }

        // handle incorrect leader case
        if (appendEntriesArg.getLeaderId() != getLeaderId()) {
            // // System.err.println("Node " + id + " says Not my leader!!!");
            // // System.err.println(appendEntriesArg.getLeaderId() + " " + getLeaderId());
            return new AppendEntriesReply(this.state.getCurrentTerm(), false);
        } else {
            resetElectionTimeout();
        }
        // append log to log entries

        // consistency check;
        boolean isConsistent = false;

        // current log index smaller than prevLogIndex index
        // or current log does not contain an entry at prevLogIndex
        // where term matches

        // // System.err.println("Append Entries Arg parameters, prev log index: " + appendEntriesArg.getPrevLogIndex()
        // + " prev log term: " +  appendEntriesArg.getPrevLogTerm());

        // appendEntriesArg.getLeaderId() != getLeaderId()
        if (this.state.getLog().lastEntryIndex() < appendEntriesArg.getPrevLogIndex() || 
            (appendEntriesArg.getPrevLogIndex() > 0 && 
                    appendEntriesArg.getPrevLogTerm() != this.state.getLog().getEntry(appendEntriesArg.getPrevLogIndex()).getTerm())) {
            // System.err.println("Inconsistent logs!");
            isConsistent = false;
        } else {
            // System.err.println("Consistent logs! Appending!");
            isConsistent = true;
        }



        if(isConsistent) {
            // append entries if consistent
            if (appendEntriesArg.getEntries() != null && appendEntriesArg.getEntries().size() != 0) {
                for (LogEntries e : appendEntriesArg.getEntries()) {
                    if (!state.getLog().append(e)) {
                        // System.err.println("Append entries fails");
                        return new AppendEntriesReply(this.state.getCurrentTerm(), false);
                    }
                }
            }

            // it should have all the log entries server has now
            // so it should be safe to apply
            if (appendEntriesArg.getLeaderCommit() > commitIndex && appendEntriesArg.getLeaderCommit() <= state.getLog().lastEntryIndex()) {
                int newCommitIndex = appendEntriesArg.getLeaderCommit();
                try {
                    applyTillNewCommitIndex(commitIndex, newCommitIndex);
                } catch (RemoteException r) {
                    r.printStackTrace();
                    return new AppendEntriesReply(this.state.getCurrentTerm(), false);
                }
            }

            if (appendEntriesArg.getEntries() != null && appendEntriesArg.getEntries().size() != 0) {
                // System.err.println("Append entries succeeds");
                // System.err.println("\n Checking log entry of node " + id + " \n");
                // state.getLog().dumpEntries();
            }
            return new AppendEntriesReply(this.state.getCurrentTerm(), true);
        } else {

            // don't delete the commited ones
            if (appendEntriesArg.getPrevLogIndex() >= commitIndex) {
                this.state.getLog().deleteConflictingEntries(appendEntriesArg.getPrevLogIndex()+1);
            }

            // System.err.println("Append entries fails");

            // System.err.println("\n Checking log entry of node " + id + " \n");
            // state.getLog().dumpEntries();

            return new AppendEntriesReply(this.state.getCurrentTerm(), false);
        }
    }



    // run periodic task: election and hearbeat
    // election will only be run if there is no leader in the cluster
    // or when the leader doesn't respond
    private void launchPeriodicTasksThread() {
        final Thread t1 = new Thread(() -> {
                try {
                    while(true) {
                        Thread.sleep(10);
                        runPeriodicHeartbeat();
                        runPeriodicElection();
                    }
                } catch (Throwable e) {
                   e.printStackTrace();
                }

        }, "RaftNode");

        t1.start();
    }


    // periodic task, for now just choose new leader
    // don't have to be synchronized
    private void runPeriodicElection() throws Exception {
        // run election as long as we don't receive heartbeat from leader

        // only FOLLOWER can start election
        if(System.currentTimeMillis() > getCurrentElectionTimeout() && (getType() == Types.FOLLOWER)) {
            startElection();
        }
    }

    private void runPeriodicHeartbeat() throws Exception {
        // run election as long as we don't receive heartbeat from leader

        // if current node is leader, periodically send heartbeat
        if (System.currentTimeMillis() > getCurrentHeartbeatTimeout() && (getType() == Types.LEADER)) {

            resetHeartbeatTimeout();
            for (int i = 0; i < num_peers; i++) {
                if (i != id)
                    sendAppendEntriesRequest(i);
            }
            commitEntry();
            resetHeartbeatTimeout();
        }
    }

    // start an election
    // this method does not have to be synchronized
    public void startElection() throws RemoteException, IOException, ClassNotFoundException {

        // transition to candidate
        this.toCandidate();

        // atomic integer vote to assure thread-safety
        AtomicInteger votes = new AtomicInteger(1);

        int lastIndex = state.getLog().lastEntryIndex();
        int lastTerm = state.getLog().getLastTerm();

        // prepare vote request
        RequestVoteArgs ra = new RequestVoteArgs(this.state.getCurrentTerm(), this.id, lastIndex, lastTerm);
        byte[] data = SerializationUtils.toByteArray(ra);

        if (num_peers > 1) {

            for(int i = 0; i < this.num_peers; i++) {
                if(i == id) continue;

                // need to persistently try until got a message
                Message msg = new Message(MessageType.RequestVoteArgs, id, i, data);

                Message cur = null;
                RequestVoteReply reply = null;

                cur = lib.sendMessage(msg);
                if (cur == null)
                    continue;

                reply = (RequestVoteReply) SerializationUtils.toObject(cur.getBody());

                if (reply.getTerm() > this.state.getCurrentTerm()) {
                    // reply has higher term, means current node cannot be leader
                    this.toFollower(reply.getTerm(), i);
                    break;
                } else if (reply.getTerm() <= this.state.getCurrentTerm()) {

                    if (reply.isVoteGranted()) {
                        votes.getAndIncrement();
                    }

                    // more than half, selected as leader
                    if(votes.get() > num_peers/2) {
                        if (getType() == Types.CANDIDATE) {
                            toLeader();
                        }

                        break;
                    }
                }
            }
        }

        // convert back to follower
        // wait for next turn
        if (this.type != Types.LEADER) {
            this.toFollower(this.state.getCurrentTerm(), getLeaderId());
        }

        // System.err.println("Election finishes, node: " + id + " vote count is: " + votes.get() + " the current leaderID is: " + getLeaderId());
    }

    // Upon wining election, send heartbeats to server
    public void broadcastTo() {
        if (getType() != Types.LEADER)
            return;

        resetHeartbeatTimeout();
        for(int i = 0; i < num_peers; i++) {
            try {
                if (i != id)
                    sendAppendEntriesRequest(i);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        resetHeartbeatTimeout();

    }

    // update term and set voted for, and convert the type
    public synchronized void toCandidate() {
        this.state.setCurrentTerm(this.state.getCurrentTerm() + 1);
        this.state.setVotedFor(this.id);
        this.type = Types.CANDIDATE;

        resetElectionTimeout();
    }


    // operations related to convert to leader
    public synchronized void toLeader() {
        nextIndex.clear();
        matchIndex.clear();
        this.type = Types.LEADER;
        leaderID = id;
        firstIndexOfTerm = this.state.getLog().lastEntryIndex() + 1;
        // reinitialize matchIndex and nextIndex
        for (int i = 0; i < num_peers; i++) {
            matchIndex.add(0);
            nextIndex.add(firstIndexOfTerm);
            assert nextIndex.get(i) != 0;
        }
        broadcastTo();
    }

    // convert current node state to follower
    // update current term
    // and update votedFor state
    // reset election timeout
    public synchronized void toFollower(int term, int leaderId) {
        this.state.setCurrentTerm(term);
        // invalidate vote when converting to follower
        this.state.setVotedFor(-1);
        this.type = Types.FOLLOWER;
        this.leaderID = leaderId;

        resetElectionTimeout();
    }


    // used to send log entry message
    // return true for success, false for failure
    public boolean sendAppendEntriesRequest(int serverId)
            throws RemoteException, ClassNotFoundException, IOException{
        boolean retry = true;

        while (retry) {
            synchronized(this) {
                if(type != Types.LEADER) return false;
                ArrayList<LogEntries> entries = new ArrayList<LogEntries>();

                // leader has more updated log
                // get all the entires after server's next index to update server
                if(this.state.getLog().lastEntryIndex() >= nextIndex.get(serverId)) {
                    entries = state.getLog().getEntryFrom(nextIndex.get(serverId));
                }

                // be careful with the corner case
                // what if nextIndex is 0?
                int prevLogIndex = Math.max(nextIndex.get(serverId)-1, 0);
                int prevLogTerm = state.getLog().getEntry(prevLogIndex) == null ? 1 : state.getLog().getEntry(prevLogIndex).getTerm();

                // System.err.println("Append Entries Request, prevlogIndex: " + prevLogIndex + " prevLogTerm: " + prevLogTerm);
                AppendEntriesArg args = new AppendEntriesArg(this.state.getCurrentTerm(),
                        this.id, prevLogIndex, prevLogTerm,
                        entries, commitIndex);


                Message msg = new Message(MessageType.AppendEntriesArg, id, serverId, SerializationUtils.toByteArray(args));
                Message re = lib.sendMessage(msg);


                if (re == null) {
                    // no response, return.
                    return false;
                }

                AppendEntriesReply res = (AppendEntriesReply) SerializationUtils.toObject(re.getBody());

                // res has higher term, give up as leader
                if(res.getTerm() > state.getCurrentTerm()) {

                    toFollower(res.getTerm(), serverId);
                    return false;

                } else {
                    if(res.isSuccess()) {

                        if (entries == null || entries.size() == 0) {
                            nextIndex.set(serverId,  Math.max(state.getLog().lastEntryIndex() + 1, 1));
                        } else {
                            // match last entry index at leader
                            matchIndex.set(serverId, state.getLog().lastEntryIndex());
                            nextIndex.set(serverId, matchIndex.get(serverId) + 1);
                        }

                        return true;
                    } else {
                        // fail because of log inconsistency, then decrement nextIndex and retry
                        // System.err.println("Decrease next index and retry");
                        if (nextIndex.get(serverId) > state.getLog().lastEntryIndex()+1) {
                            int decreasedIndex = Math.max(state.getLog().lastEntryIndex() + 1, 1);
                            nextIndex.set(serverId, decreasedIndex);

                            // System.err.println("Index decreased to: " + nextIndex.get(serverId));
                        } else if (nextIndex.get(serverId) > matchIndex.get(serverId)) {
                            // cannot go back more than commitIndex
                            nextIndex.set(serverId, nextIndex.get(serverId) - 1);

                            // System.err.println("Index decreased to: " + nextIndex.get(serverId));
                        } else if (nextIndex.get(serverId) <= matchIndex.get(serverId)) {

                            // cannot append since we cannot rollback commits
                            // System.err.println("Cannot append over committed entries!");
                            return false;
                        }
                    }
                }

            }
        }

        return false;
    }

    // append log entires to peers and commit if majority accepts

    public boolean appendEntriesToPeersAndCommit() {
        // System.err.println("Appending entries to peers");

        // System.err.println("\n Checking log entry of node " + id + " \n");
        // state.getLog().dumpEntries();

        resetHeartbeatTimeout();
        int count = 1;
        for(int i = 0; i < num_peers; i++) {
            if(i == id) continue;
            try {
                if (sendAppendEntriesRequest(i))
                    count++;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        resetHeartbeatTimeout();


        return true;
    }

    public synchronized boolean commitEntry() throws RemoteException {
        // System.err.println("Commiting entries");

        if(type != Types.LEADER) return false;

        // commit till the majority of match index that is larger than commitIndex
        ArrayList<Integer> copy = new ArrayList<>(matchIndex);
        Collections.sort(copy);

        // System.err.println("\n Checking match index \n ");
        // for (int i = 0; i < matchIndex.size(); i++)
        //     // System.err.print(matchIndex.get(i) + " ");
        // System.err.println(" \n ");


        int matchIndexSize = matchIndex.size();

        int pos = matchIndexSize % 2 == 0 ? matchIndexSize / 2 - 1 : matchIndexSize / 2;

        // skip one because leader does not have match index
        int newCommitIndex = copy.get(pos+1);

        // System.err.println("oldCommitIndex: " + commitIndex + " newCommitIndex: " + newCommitIndex);
        // System.err.println("lastEntry: " + state.getLog().lastEntryIndex());

        // dont commit if new commit index is smaller, or when the term is different
        if (commitIndex >= newCommitIndex || state.getLog().getEntry(newCommitIndex).getTerm() != state.getCurrentTerm()) {
            // System.err.println("Cannot commit to local");
            return false;
        }

        applyTillNewCommitIndex(commitIndex, newCommitIndex);

        // need to send again to make peers commit
        resetHeartbeatTimeout();
        for(int i = 0; i < num_peers; i++) {
            if(i == id) continue;
            try {
                sendAppendEntriesRequest(i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        resetHeartbeatTimeout();

        return true;
    }

    public synchronized void applyTillNewCommitIndex(int oldCommitIndex, int newCommitIndex) throws RemoteException {
        // System.err.println("Trying to apply till new commit index");
        for(int i = oldCommitIndex + 1; i <= newCommitIndex; i++) {
            ApplyMsg msg = new ApplyMsg(id, i, state.getLog().getEntry(i).getCommand(), false, null);
            lib.applyChannel(msg);
        }

        // System.err.println("\n Apply done \n");
        // System.err.println("\n Checking log entry of node " + id + " \n");
        // state.getLog().dumpEntries();

        commitIndex = newCommitIndex;
        lastApplied = commitIndex;
    }

    public synchronized boolean isCommittable(int index) {
        int majority = 1 + num_peers/2;
        int cnt = 0;

        for(int i = 0; i < num_peers; i++) {
            if(matchIndex.get(i) >= index) {
                cnt++;
            }
            if(cnt >= majority) return true;
        }
        return cnt >= majority;
    }

    // start called at leader to add a new operation to the log

    @Override
    public StartReply start(int command) {
        int term = this.state.getCurrentTerm();
        int index = -1;
        boolean isLeader = getType() == Types.LEADER;

        // not a leader, cannot start adding log
        if(!isLeader) {
            return new StartReply(index, term, false);
        }

        // check current log, starting from the last committed index
        // if this command already exists, just reply true
        for(int i = getCommitIndex() + 1; i <= this.state.getLog().lastEntryIndex(); i++) {
            if (state.getLog().getEntry(i).getCommand() == command){
                state.getLog().getEntry(i).setTerm(term);
                // System.err.println("Entry exists, return true");
                return new StartReply(i, term, true);
            }
        }

        // System.err.println("Entry does not exist, appending");

        // append entry since it doesn't exist
        index = this.state.getLog().lastEntryIndex() + 1;
        LogEntries entry = new LogEntries(term, index, command);
        this.state.getLog().append(entry);

        return new StartReply(state.getLog().lastEntryIndex(), term, true);
    }

    @Override
    public GetStateReply getState() {
        GetStateReply gr = new GetStateReply(this.state.getCurrentTerm(), this.getType() == Types.LEADER);
        return gr;
    }

    // relay message to correct handler
    @Override
    public Message deliverMessage(Message message) {

        if (message == null || message.getType() == null || message.getBody() == null
                || message.getDest() != id || message.getType() == MessageType.RequestVoteReply
                || message.getType() == MessageType.AppendEntriesReply) {

            return null;
        }

        if (message.getType() == MessageType.RequestVoteArgs) {
            RequestVoteArgs cur = null;
            try {
                cur = (RequestVoteArgs) SerializationUtils.toObject(message.getBody());
            } catch (Exception e) {
                e.printStackTrace();
            }

            RequestVoteReply res  = this.requestVote(cur);

            byte[] data = null;
            try {
                 data = SerializationUtils.toByteArray(res);
            } catch (Exception e) {
                e.printStackTrace();
            }

            Message reply = new Message(MessageType.RequestVoteReply, id, message.getSrc(), data);

            return reply;
        } else if (message.getType() == MessageType.AppendEntriesArg) {

            AppendEntriesArg aa = null;
            try {
                aa = (AppendEntriesArg) SerializationUtils.toObject(message.getBody());
            } catch (Exception e) {
                e.printStackTrace();
            }

            AppendEntriesReply ar = this.AppendEntries(aa);

            byte[] data = null;

            try {
                data = SerializationUtils.toByteArray(ar);
            } catch (Exception e) {
                e.printStackTrace();
            }

            Message reply = new Message(MessageType.AppendEntriesReply, id, message.getSrc(), data);

            return reply;
        } else {
            return null;
        }

    }

    //main function
    public static void main(String args[]) throws Exception {
        if (args.length != 3) throw new Exception("Need 2 args: <port> <id> <num_peers>");
        //new usernode
        RaftNode UN = new RaftNode(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    }
}
