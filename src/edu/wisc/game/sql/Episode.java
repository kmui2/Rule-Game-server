package edu.wisc.game.sql;

import java.io.*;
import java.util.*;
import java.text.*;

import javax.json.*;
import javax.persistence.*;


import edu.wisc.game.util.*;
import edu.wisc.game.reflect.*;
import edu.wisc.game.engine.*;
import edu.wisc.game.parser.*;
import edu.wisc.game.sql.Board.Pos;
import edu.wisc.game.engine.RuleSet.BucketSelector;
import edu.wisc.game.formatter.*;

import javax.xml.bind.annotation.XmlElement; 
//import javax.xml.bind.annotation.XmlRootElement;


/** An Episode is a single instance of a Game played by a person or machine 
    with our game server. It describes the current state of the game, and has methods
    for processing player's actions.
*/
@Entity
public class Episode {

        @Id 
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private long id;


    /** This is used to assign episode IDs, which are unique within a
	given server run. The IDs are not meant to be persistent.
    */
    @Basic
    public String episodeId;
    public String getEpisodeId() { return episodeId; }
    public void setEpisodeId(String _episodeId) { episodeId = _episodeId; }
 
    // When this Episode was created
    Date startTime;    
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date _startTime) { startTime = _startTime; }

    // The number of buckets
    static final int NBU = Board.buckets.length; // 4
    
    public static class Move {
	/** The position of the piece being moved, in the [1:N*N] range */
	int pos;
	int pieceId = -1;
	public int getPos() { return pos; }
        public int getPieceId() { return pieceId; }
 	/** (Attempted) destination, in the [0:3] range */
	int bucketNo;
	public int getBucketNo() { return bucketNo ; }
 	Move(int _pos, int b) { pos = _pos; bucketNo = b; }
	Piece piece =  null;
	/** Acceptance code; will be recorded upon processing */
	int code;
 	public int getCode() { return code; }
	Date time = new Date();
	public String toString() {
	    return "MOVE " + pos + " to B" + bucketNo;
	}
   }
    
    final RuleSet rules;
    /** The current board: an array of N*N+1 elements (only positions
	[1..N*N] are used), with nulls for empty cells and non-nulls
	for positions where pieces currently are. */
    @Transient
    private Piece[] pieces = new Piece[Board.N*Board.N + 1];
    /** Pieces are moved into this array once they are removed from the board.
	This is only shown in web UI.
     */
    @Transient
    private Piece[] removedPieces = new Piece[Board.N*Board.N + 1];

    /** The count of all attempts done so far, including successful and unsuccessful */
    int attemptCnt=0;
    /** All successful moves so far */
    int doneMoveCnt;
    @Transient
    Vector<Move> transcript = new Vector<>();
  
    /** Set when appropriate */
    boolean stalemate = false;
    boolean cleared = false;
    boolean givenUp = false;
    
    /** Which bucket was the last one to receive a piece of a given color? */
    @Transient
    private HashMap<Piece.Color, Integer> pcMap = new HashMap<>();
    /** Which bucket was the last  one to receive a piece of a given shape? */
    @Transient
    private HashMap<Piece.Shape, Integer> psMap = new HashMap<>();
    /** Which bucket was the last one to receive a piece? */
    @Transient
    private Integer pMap=null;

    /** Which row of rules do we look at now? (0-based) */
    @Transient
    protected int ruleLineNo = 0;

    @Transient
    private RuleLine ruleLine = null;

    /** Will return true if this is, apparently, an episode restored
	from SQL server, and cannot be played anymore because the boad
	position and the egine state (ruleLine) is not persisted. This
	is a rare event; it may only become an issue if an episode for
	some reason became persisted before it was finished (this is
	rare; can be caused by cascading e.g. on an "Accept Bonus" event), 
	and then the web app was restarted before the episode
	was finished (this is even rarer).
    */
    boolean isNotPlayable() {
	return ruleLine == null;
    }
    
    
    /** Our interface to the current rule line. It includes the underlying 
	rule line and the current counters which indicate how many times
	the atoms in that line have been used. 	When pieces are removed, this
	structure updates itself, until it cannot pick any pieces anymore. */
    class RuleLine {
	final RuleSet.Row row;
	private int ourGlobalCounter;
	private int ourCounter[];

	public String toString() {
	    Vector<String> v = new Vector<>();
	    for(int k:  ourCounter) v.add("" + k);
	    return "[RL: " + row.toSrc() + " / " +  ourGlobalCounter + " / " + String.join(",", v) + "]";
	}

	
	RuleLine(RuleSet rules, int rowNo) {
	    if (rowNo < 0 || rowNo >= rules.rows.size()) throw new IllegalArgumentException("Invalid row number");
	    row = rules.rows.get(rowNo);
	    ourGlobalCounter = row.globalCounter;
	    ourCounter = new int[row.size()];
	    for(int i=0; i<ourCounter.length; i++) ourCounter[i] = row.get(i).counter;
	    doneWith = exhausted() || !buildAcceptanceMap();
	    //	    System.err.println("Constructed "+ this+"; exhausted()=" + exhausted()+"; doneWith =" + doneWith);
	}

	/** This is set once all counters are exhausted, or no more
	    pieces can be picked by this rule. The caller should check
	    this flag after every use, and advance ruleLine */
	boolean doneWith =false;

	/** [pos][atomNo] */
	private BitSet[][] acceptanceMap = new BitSet[Board.N*Board.N+1][];
	private boolean[] isMoveable = new boolean[Board.N*Board.N+1];

	/** For each piece, where can it go? (OR of all rules) */
	BitSet[] moveableTo() {
	    BitSet[] q =  new BitSet[Board.N*Board.N+1];
	    for(int pos=0; pos<pieces.length; pos++) {
		q[pos] = new BitSet();
		if (pieces[pos]!=null) {
		    BitSet r = new BitSet(); // to what buckets this piece can go
		    for(BitSet b: acceptanceMap[pos]) {
			q[pos].or(b);
		    }	

		}
	    }
	    return q;	    
	}
	
	/** For each piece currently on the board, find which rules
	    allow it to be moved, and to where
	    @return true if at least one piece can be moved
	*/
	private boolean buildAcceptanceMap() {
	    for(int pos=0; pos<pieces.length; pos++) {
		if (pieces[pos]==null) {
		    acceptanceMap[pos]=null;
		    isMoveable[pos]=false;
		} else {
		    acceptanceMap[pos] = pieceAcceptance(pieces[pos]);
		    BitSet r = new BitSet(); // to what buckets this piece can go
		    for(BitSet b: acceptanceMap[pos]) {
			r.or(b);
		    }
		    isMoveable[pos]= !r.isEmpty();
		}
	    }
	    return  isAnythingMoveable();
	}

	/** Looks at the current acceptance map to see if any of the 
	    currently present pieces can be moved */
	private boolean isAnythingMoveable() {
	    for(int pos=0; pos<pieces.length; pos++) {
		if (pieces[pos]!=null && isMoveable[pos]) return true;
	    }
	    return false;	
	}

	
	/**
	   @return result[j] is the set of buckets into which the j-th
	   rule (atom) allows the specified piece to be moved.
	*/
	private BitSet[] pieceAcceptance(Piece p) {
	    //	    System.err.println("pieceAcceptance(p=" +p+")");
	    if (doneWith) throw new IllegalArgumentException("Forgot to scroll?");

	    if (row.globalCounter>=0 &&  ourGlobalCounter<=0)  throw new IllegalArgumentException("Forgot to set the scroll flag on 0 counter!");
	    
	    // for each rule, the list of accepting buckets
	    BitSet whoAccepts[] = new BitSet[row.size()];
	    Pos pos = p.pos();
	    BucketVarMap  varMap = new  BucketVarMap(p);

	    //System.err.println("varMap=" + varMap);

	    
	    for(int j=0; j<row.size(); j++) {
		whoAccepts[j] = new BitSet(NBU);
		RuleSet.Atom atom = row.get(j);
		if (atom.counter>=0 && ourCounter[j]==0) continue;
		if (atom.shape!=null && atom.shape!=p.getShape()) continue;
		if (atom.color!=null && atom.color!=p.getColor()) continue;

		if (!atom.plist.allowsPicking(pos.num(), eligibleForEachOrder)) continue;
		BitSet d = atom.bucketList.destinations( varMap);
		whoAccepts[j].or(d);
		//	System.err.println("pieceAcceptance(p=" +p+"), dest="+d+", whoAccepts["+j+"]=" + 	whoAccepts[j]);
	    }
	    return whoAccepts;
	}


	/** Request acceptance for this move. Returns result (accept/deny);
	    in case of acceptance, decrements appropriate counters */
	int accept(Move move) {

	    //	    System.err.println("Accept: "+this+", move="+ move);
	    
	    if (doneWith) throw  new IllegalArgumentException("Forgot to scroll?");
	    transcript.add(move);
	    attemptCnt++;

	    move.piece =pieces[move.pos];
	    if (move.piece==null) return move.code=CODE.EMPTY_CELL;	    
	    move.pieceId = (int)move.piece.getId();
	    
	    BitSet[] r = acceptanceMap[move.pos];
	    Vector<Integer> acceptingAtoms = new  Vector<>();
	    Vector<String> v = new Vector<>();
	    for(int j=0; j<row.size(); j++) {
		if (r[j].get(move.bucketNo)) {
		    v.add("" + j + "(c=" + ourCounter[j]+")");
		    acceptingAtoms.add(j);
		    if (ourCounter[j]>0) {
			ourCounter[j]--;
		    }
		}
	    }
	    
	    //	    System.err.println("Acceptors: " + String.join(" ", v));
	    if (acceptingAtoms.isEmpty()) return  move.code=CODE.DENY;

	    //--- Process the acceptance -------------------------
	    if (ourGlobalCounter>0) ourGlobalCounter--;

	    doneMoveCnt++;

	    // Remember where this piece was moved
	    pcMap.put(move.piece.getColor(), move.bucketNo);
	    psMap.put(move.piece.getShape(), move.bucketNo);
	    pMap = move.bucketNo;
	    
	    removedPieces[move.pos] = pieces[move.pos];
	    removedPieces[move.pos].setDropped(move.bucketNo);
	    pieces[move.pos] = null; // remove the piece
	    
	    // Check if this rule can continue to be used, and if so,
	    // update its acceptance map
	    doneWith = exhausted() || !buildAcceptanceMap();
	    //System.err.println("doneWith=" + doneWith);
	    
	    //System.err.println("Episode.accept: move accepted. card=" + onBoard().cardinality());
	    cleared = (onBoard().cardinality()==0);
	    
	    //	    Logging.info("Accepted, return " + CODE.ACCEPT);
	    return  move.code=CODE.ACCEPT;
	}

	/** Is this row of rules "exhausted", based either on the
	    global counter for the row, or the individual rules?
	    "Control moves to the next row when either all counters OR
	    the global counter reach zero", as per Paul's specs.
	*/
	boolean exhausted() {
	    if (row.globalCounter>=0 && ourGlobalCounter==0) return true;
	    for(int j=0; j<row.size(); j++) {
		if (row.get(j).counter<0) return false; // "*" cannot be exhaustd
		if (ourCounter[j]>0) return false;
	    }
	    return true;
	}
	
    }
    
  
    
    /** Contains the values of various variables that may be used in 
	finding the destination buckets for a given piece */
    class BucketVarMap extends HashMap<String, HashSet<Integer>> {
	
	private void pu( BucketSelector key, int k) {
	    HashSet<Integer> h = new  HashSet<>();
	    h.add(k);
	    put(key.toString(), h);
	}
	
	/** Puts together the values of the variables that may be used in 
	    finding the destination buckets */
	BucketVarMap(Piece p) {    
	    if (pcMap.get(p.getColor())!=null) pu(BucketSelector.pc, pcMap.get(p.getColor()));
	    if (psMap.get(p.getShape())!=null) pu(BucketSelector.ps, psMap.get(p.getShape()));
	    if (pMap!=null) pu(BucketSelector.p, pMap);
	    Pos pos = p.pos();
	    put(BucketSelector.Nearby.toString(), pos.nearestBucket());
	    put(BucketSelector.Remotest.toString(), pos.remotestBucket());
	}


	public String toString() {
	    Vector<String> v = new Vector<>();
	    for(String key: keySet()) {
		Vector<String> w = new Vector<>();
		for(int z:  get(key)) w.add("" + z);
		v.add("["+key+":"+ Util.join(",", w)+ "]");
	    }
	    return String.join(" ", v);
	}
	
    }

    @Transient
    private OutputMode outputMode;
    @Transient
    private final PrintWriter out;
    @Transient
    private final Reader in;
   
    private static DateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
    /** with milliseconds */
    private static DateFormat sdf2 = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");

    /** Creates a more or less unique string ID for this Episode object */
    private String buildId() {
	String s = sdf.format(startTime) + "-";
	for(int i=0; i<6; i++) {
	    int k =  Board.random.nextInt(10 + 'Z'-'A'+1);
	    char c = (k<10) ? (char)('0' + k) : (char)('A' + k-10);
	    s += c;
	}
	return s;
    }

    /** Dummy constructor; only used for error code production */
    public Episode() {
	episodeId=null;
	rules=null;
	out=null;
	in=null;
	nPiecesStart = 0;
	startTime = new Date();    
    };

    /** The initial number of pieces on the board */
    @Basic
    private int nPiecesStart;
    public int getNPiecesStart() { return nPiecesStart; }
    public void setNPiecesStart(int _nPiecesStart) { nPiecesStart = _nPiecesStart; }


    /** Creates a new Episode for a given Game (which defines rules and the 
	properties of the initial board). */
    public Episode(Game game, OutputMode _outputMode, Reader _in, PrintWriter _out) {
	startTime = new Date();    
	in = _in;
	out = _out;
	outputMode = _outputMode;

	episodeId = buildId();
	
	rules = game.rules;
	Board b =  game.initialBoard;
	if (b==null) b = new Board( game.randomObjCnt, game.nShapes, game.nColors);
	nPiecesStart = b.getValue().size();
	for(Piece p: b.getValue()) {
	    Pos pos = p.pos();
	    pieces[pos.num()] = p;
	}
	doPrep();
	
    }

    /** Return codes for the /move and /display API web API calls,
	and for the MOVE command in the captive game server.
     */
    public static class CODE {
	public static final int
	/** move accepted and processed */
	    ACCEPT = 0,
	/** Move rejected, and no other move is possible
	    (stalemate). This means that the rule set is bad, and we
	    owe an apology to the player */
	    STALEMATE=2,
	/** move rejected, because there is no piece in the cell */
	    EMPTY_CELL= 3,
	/** move rejected, because this destination is not allowed */
	    DENY = 4,
	/** Exit requested */
	    EXIT = 5,
	/** New game requested */
	    NEW_GAME = 6
	    ;
	
	public static final int
	    INVALID_COMMAND= -1,
	    INVALID_ARGUMENTS= -2,
	    INVALID_POS= -3,
	// No game is on now. Start a game first!
	    NO_GAME = -4,
	// Used in socket server GAME  command
	    INVALID_RULES = -5,
	// Used in web app, when trying to access a non-existent episode
	    NO_SUCH_EPISODE = -6,
	// The number of preceding attempts does not match. This may indicate
	// that some HTTP requests have been lost, or a duplicate request
	    ATTEMPT_CNT_MISMATCH = -7,
	// This code is returned on successful DISPLAY calls, to
	// indicate that it was a display (no actual move requested)
	// and not a MOVE	    
	    JUST_A_DISPLAY = -8;
	    
    }

    public static class FINISH_CODE {
	public static final int
	/** there are still pieces on the board, and some are moveable */
	    NO = 0,
	/** no pieces left on the board */
	    FINISH = 1,
	/** there are some pieces on the board, but none can be moved anymore */
	    STALEMATE=2,
	/** The player has said he does not want to play any more. This
	    may only happen in some GUI versions. */
	    GIVEN_UP =3;
    }

    /** Creates a bit set with bits set in the positions where there are
	pieces */
    private BitSet onBoard() {
	BitSet onBoard = new BitSet(Board.N*Board.N+1);
	for(int i=0; i<pieces.length; i++) {
	    if (pieces[i]!=null) onBoard.set(i);
	}
	return onBoard;
    }

    @Transient
    private boolean prepReady = false;

    
    /** At present, which pieces are eligible for picking under each of
	the existing orders? This structure needs to be updated every time
	a piece is removed from the board.
    */
    private class EligibilityForOrders extends HashMap<String, BitSet>  {
	/** Finds pieces eligible for pick up under each orders based on
	    the current board content */
	void update() {
	    clear();
	    // Which pieces may be currently picked under various ordering schemes?
	    for(String name: rules.orders.keySet()) {
		Order order = rules.orders.get(name);
		BitSet eligible = order.findEligiblePieces(onBoard());
		put(name,  eligible);
	    }
	}
    }

    @Transient
    private EligibilityForOrders eligibleForEachOrder = new EligibilityForOrders();

    /** Run this method at the beginning of the game, and
	every time a piece has been removed, to update various
	auxiliary data structures.
	@return true, unless stalemeate (no piece can be picked) is
	detected, in which case it return false
    */
    private boolean doPrep() {

	// Which pieces currently on the border can be picked under
	// various ordering schemes?
	eligibleForEachOrder.update();

	boolean mustUpdateRules = (ruleLine==null || ruleLine.doneWith);

	//System.err.println("doPrep: mustUpdateRules=" + mustUpdateRules +", ruleLineNo="+ ruleLineNo);


	
	if (mustUpdateRules) {
	    ruleLineNo= (ruleLine==null) ? 0: (ruleLineNo+1) %rules.rows.size();
	    ruleLine = new RuleLine(rules, ruleLineNo);
	    //	    System.err.println("doPrep: updated ruleLineNo="+ ruleLineNo+", doneWith=" + ruleLine.doneWith);

	    final int no0 = ruleLineNo;
	    // if the new rule is exhausted, or allows no pieces to be moved,
	    // scroll on
	    //	    System.out.println("# BAM(line " + ruleLineNo + ")");
	    while( ruleLine.doneWith) {
		 ruleLineNo=  (ruleLineNo+1) %rules.rows.size();
		 //		 System.err.println("# BAM(scroll to line " + ruleLineNo + ")");
		 // If we have checked all lines, and no line contains
		 // a rule that can move any piece, then it's a stalemate
		 if (ruleLineNo==no0) {
		     stalemate=true;
		     return false;
		 }
		 ruleLine = new RuleLine(rules, ruleLineNo);
	    }
	}
	
	prepReady = true;
	return true;
    }

    @Transient
    private Move lastMove = null;
    
    private int accept(Move move) {
	lastMove = move;
	if (stalemate) {
	    return CODE.STALEMATE;
	}
	if (move.pos<1 || move.pos>=pieces.length) return CODE.INVALID_POS;
	// FIXME code here
	int code = ruleLine.accept(move);

	if (code==CODE.ACCEPT && !cleared && !stalemate && !givenUp) {
	    doPrep();
	}
	
	return code;
    }

    private static HTMLFmter  fm = new HTMLFmter(null);


    /** Graphic display of the board */
    public String graphicDisplay() {
	return graphicDisplay(false);
    }
    
    public String graphicDisplay(boolean html) {

	if (isNotPlayable()) {
	    return "This episode must have been restored from SQL server, and does not have the details necessary to show the board";
	}

	
	Vector<String> w = new Vector<>();

	String div = "#---+";
	for(int x=1; x<=Board.N; x++) div += "-----";
	w.add(div);
	
	
	for(int y=Board.N; y>0; y--) {
	    String s = "# " + y + " |";
	    for(int x=1; x<=Board.N; x++) {
		int pos = (new Pos(x,y)).num();
		String z = " .";
		if (pieces[pos]!=null) {
		    Piece p = pieces[pos];
		    z = p.getShape().symbol();
		    z =  html?
			fm.wrap("strong",fm.colored( p.getColor().toString().toLowerCase(), z)) :
			p.getColor().symbol() + z;
		}

		z = (lastMove!=null && lastMove.pos==pos) ?    "[" + z + "]" :
		    ruleLine.isMoveable[pos]?     "(" + z + ")" :
		    " " + z + " ";

		s += " " + z;
	    }
	    w.add(s);
	}
	w.add(div);
	String s = "#   |";
	for(int x=1; x<=Board.N; x++) s += "   " + x + " ";
	w.add(s);
	return String.join("\n", w);
    }

    int getFinishCode() {
	 return cleared? FINISH_CODE.FINISH :
	     stalemate? FINISH_CODE.STALEMATE :
	     givenUp?  FINISH_CODE.GIVEN_UP :
	     FINISH_CODE.NO;
    }
       

    private void respond(int code, String msg) {
	String s = "" + code + " " + getFinishCode() +" "+attemptCnt;
	if (msg!=null) s += "\n" + msg;
	out.println(s);
    }    
    

    Board getCurrentBoard(boolean showRemoved) {
	return ruleLine==null? null:
	    showRemoved? new Board(pieces, removedPieces, ruleLine.moveableTo()): 
	    new Board(pieces, null, ruleLine.moveableTo());
    }

    /** Shows tHe current board (without removed [dropped] pieces) */
    public Board getCurrentBoard() {
	return getCurrentBoard(false);
    }

    /** No need to show this field */
    private static final HashSet<String> excludableNames =  Util.array2set("dropped");
    
    private String displayJson() {
	Board b = getCurrentBoard();
	JsonObject json = JsonReflect.reflectToJSONObject(b, true, excludableNames);
	return json.toString();
    }

    static final String version = "1.021";

    private String readLine( LineNumberReader​ r) throws IOException {
	out.flush();
	return r.readLine();
    }

    /** Can be used to display the current state of the episode */
    public class Display     {
	// The following describe the state of this episode, and are only used in the web GUI
	int finishCode = Episode.this.getFinishCode();
	Board board =  getCurrentBoard();

	/** What pieces are on the board now, and what pieces have been removed */
        public Board getBoard() { return board; }
        @XmlElement
        public void setBoard(Board _b) { board = _b; }

	/** Is this episode still continues (code 0), has stalemated (2), or has the board been cleared (4)? */
	public int getFinishCode() { return finishCode; }
    
	int code;
	String errmsg;

	/** On a /move call: Has this move been accepted or rejected? (When returned by /display response, the value is -8). */
        public int getCode() { return code; }
        @XmlElement
        public void setCode(int _code) { code = _code; }
	/** The error or debug messsage, if any */
        public String getErrmsg() { return errmsg; }
        @XmlElement
        public void setErrmsg(String _msg) { errmsg = _msg; }

       	int numMovesMade = Episode.this.attemptCnt;
	/** How many move attempts (successful or not) has been made so far */
        public int getNumMovesMade() { return numMovesMade; }
        @XmlElement
        public void setNumMovesMade(int _numMovesMade) { numMovesMade = _numMovesMade;}

	private Vector<Move> transcript =  Episode.this.transcript;
	/** The list of all move attempts (successful or not) done so far
	    in this episode */
	public Vector<Move> getTranscript() { return transcript; }


	public Display(int _code, 	String _errmsg) {
	    code = _code;
	    errmsg = _errmsg;
	}
    }

    /** Builds a display to be sent out over the web UI */
    public Display mkDisplay() {
    	return new Display(Episode.CODE.JUST_A_DISPLAY, "Display requested");
    }
 
    
    public Display doMove(int y, int x, int by, int bx, int _attemptCnt) throws IOException {
	if (cleared || stalemate || givenUp) {
	    return new Display(CODE.NO_GAME, "No game is on right now (cleared="+cleared+", stalemate="+stalemate+"). Use NEW to start a new game");
	}

	if (_attemptCnt != attemptCnt)  return new Display(CODE.ATTEMPT_CNT_MISMATCH, "Given attemptCnt="+_attemptCnt+", expected " + attemptCnt);
		
	boolean invalid=false;
	if (x<1 || x>Board.N) return new Display(CODE.INVALID_ARGUMENTS, "Invalid input: column="+x+" out of range");
	if (y<1 || y>Board.N) return new Display(CODE.INVALID_ARGUMENTS, "Invalid input: row="+y+" out of range");
	if (bx!=0 && bx!=Board.N+1) return new Display(CODE.INVALID_ARGUMENTS, "Invalid input: bucket column="+bx+" is not 0 or "+(Board.N+1));
	if (by!=0 && by!=Board.N+1) return new Display(CODE.INVALID_ARGUMENTS, "Invalid input: bucket row="+by+" is not 0 or "+(Board.N+1));

	Pos pos = new Pos(x,y), bu =  new Pos(bx, by);
	int buNo=bu.bucketNo();
	if (buNo<0 || buNo>=Board.buckets.length) {
	    return new Display(CODE.INVALID_ARGUMENTS, "Invalid bucket coordinates");
	}
	Move move = new Move( pos.num(), buNo);
	int code = accept(move);
	String msg =
	    (outputMode==OutputMode.BRIEF) ? null:
	    cleared?  "Game cleared - the board is clear" :
	    stalemate?  "Stalemate - no piece can be moved any more. Apology for these rules!" :
	    givenUp? "You have given up this episode" :
	    null;
	return new Display(code, msg);
    }

    
    /** Lets this episode play out until either all pieces are cleared, or
	a stalemate is reached, or the player gives up (sends an EXIT or NEW command)
	@return true if another game is requested */
    public boolean playGame(int gameCnt) throws IOException {
	try {
	String msg = "# Hello. This is Captive Game Server ver. "+version+". Starting a new game (no. "+gameCnt+")";
	if (stalemate) {
	    respond(CODE.STALEMATE, msg + " -- immediate stalemate. Our apologies!");
	} else {
	    respond(CODE.NEW_GAME, msg);
	}
	out.println(displayJson());
	if (outputMode==OutputMode.FULL) out.println(graphicDisplay());
	
	LineNumberReader​ r = new LineNumberReader​(in);
	String line = null;
	while((line=readLine(r))!=null) {
	    line = line.trim();
	    if (line.equals("")) continue;
	    Vector<Token> tokens;
	    try {
		tokens    = Token.tokenize(line);
	    } catch(RuleParseException ex) {
		respond(CODE.INVALID_COMMAND,"# Invalid input - cannot parse");
		continue; 		
	    }
	    if (tokens.size()==0 || tokens.get(0).type!=Token.Type.ID) {
		respond(CODE.INVALID_COMMAND, "# Invalid input");
		continue;
	    }
	    String cmd = tokens.get(0).sVal.toUpperCase();
	    if (cmd.equals("EXIT")) {
		respond(CODE.EXIT, "# Goodbye");
		return false;
	    } else if (cmd.equals("VERSION")) {
		out.println("# " + version);
	    } else if (cmd.equals("NEW")) {
		return true;
	    } else if (cmd.equals("HELP")) {		
		out.println("# Commands available:");
		out.println("# MOVE row col bucket_row bucket_col");
		out.println("# NEW");
		out.println("# DISPLAY");
		out.println("# DISPLAYFULL");
		out.println("# MODE <BRIEF|STANDARD|FULL>");
		out.println("# EXIT");
	    } else if (cmd.equals("DISPLAY")) {
		out.println(displayJson());
		if (outputMode==OutputMode.FULL) out.println(graphicDisplay());
	    } else if (cmd.equals("DISPLAYFULL")) {
		out.println(displayJson());
		out.println(graphicDisplay());
	    } else if (cmd.equals("MODE")) {
		tokens.remove(0);
		if (tokens.size()!=1 || tokens.get(0).type!=Token.Type.ID) {
		    respond(CODE.INVALID_ARGUMENTS, "# MODE command must be followed by the new mode value");
		    continue;
		}
		String s = tokens.get(0).sVal.toUpperCase();
		try {
		    outputMode= Enum.valueOf( OutputMode.class, s);
		} catch(IllegalArgumentException ex) {
		    respond(CODE.INVALID_ARGUMENTS, "# Not a known mode: " + s);
		    continue;
		}
		out.println("# OK, mode=" + outputMode);
	    } else if (cmd.equals("MOVE")) {
		
		tokens.remove(0);
		if (tokens.size()!=4) {
		    respond(CODE.INVALID_ARGUMENTS, "# Invalid input");
		    continue;
		}
		int q[] = new int[4];
		// y x By Bx

		boolean invalid=false;
		for(int j=0; j<4; j++) {
		    if (tokens.get(j).type!=Token.Type.NUMBER) {
			respond(CODE.INVALID_ARGUMENTS, "# Invalid input: "+tokens.get(j));
			invalid=true;
			break;
		    }
		    q[j] = tokens.get(j).nVal;

		}
		if (invalid) continue;

		Display mr = doMove(q[0], q[1], q[2], q[3], attemptCnt);
		respond(mr.code, "# " + mr.errmsg);
		if (outputMode!=OutputMode.BRIEF) out.println(displayJson());
		if (outputMode==OutputMode.FULL) out.println(graphicDisplay());
		
	    } else {
		respond(CODE.INVALID_COMMAND, "# Invalid command: " +cmd);
		continue;
	    }
	}
	} finally {
	    out.flush();
	}
	return false;
    }

    public enum OutputMode {
	// One-line response on MOVE; no comments
	BRIEF,
	// Two-line response on MOVE; no comments
	STANDARD,
	// Two-line response on MOVE; human-readable comments
	FULL}; 

    /** @return true if this episode cannot accept any more move attempts */
    boolean isCompleted() {
	return cleared || stalemate || givenUp;
    }

    void giveUp() {
	if (!cleared && !stalemate) givenUp = true;
    }

   /** Let's just write one file at a time */
    static private final String file_writing_lock = "Board file writing lock";
	
    /* Saves all the recorded moves (the transcript of the episode) in a CSV file.
       <pre>
       transcripts/pid.transcript.csv
      pid,episodeId,moveNo,y,x,by,bx,code
</pre>
    */    
    void saveTranscriptToFile(String pid, String eid, File f) {
	synchronized(file_writing_lock) {
	try {	    
	    PrintWriter w = new PrintWriter(new	FileWriter(f, true));
	    if (f.length()==0) w.println("#pid,episodeId,moveNo,timestamp,y,x,by,bx,code");
	    Vector<String> v = new Vector<>();
	    int k=0;
	    for(Move move: transcript) {
		v.clear();
		v.add(pid);
		v.add(eid);
		v.add(""+(k++));
		v.add( sdf2.format(move.time));
		Board.Pos q = new Board.Pos(move.pos);
		v.add(""+q.y);
		v.add(""+q.x);
		Board.Pos b = Board.buckets[move.bucketNo];
		v.add(""+b.y);
		v.add(""+b.x);
		v.add(""+move.code);
		w.println(String.join(",", v));
	    }
	    w.close();
	} catch(IOException ex) {
	    System.err.println("Error writing the transcript: " + ex);
	    ex.printStackTrace(System.err);
	}	    
	}  
    }


    


    
}
