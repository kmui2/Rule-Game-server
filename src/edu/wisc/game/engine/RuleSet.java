package edu.wisc.game.engine;

import java.io.*;
import java.util.*;
import java.text.*;

import edu.wisc.game.util.Util;
import edu.wisc.game.sql.*;
import edu.wisc.game.parser.*;

public class RuleSet {

    enum BucketSelector {
	p, pc, ps,
	//Nearby= into the nearest bucket
	Nearby,
	//Remotest=into the farthest bucket
	Remotest}

    
    static class PositionList {
	/** If true, there is no restriction */
	final boolean any;
	Vector<Integer> list1 = new Vector<>();
	/** The keys can be  PositionSelector names or user-defined order
	    names */
	Vector<String> list2 = new Vector<>();

	public String toString() {
	    return "[" + Util.joinNonBlank(",", list1) + "; " +
		Util.joinNonBlank(",",list2) + "]";	       
	}
	
	private void addAll(PositionList q) throws RuleParseException {
	    if (any || q.any) throw new RuleParseException("When describing position lists, '*' cannot be used in combinations");
	    list1.addAll(q.list1);
	    list2.addAll(q.list2);
	}
	PositionList(Expression ex, TreeMap<String, Order> orders)  throws RuleParseException {
	    any = (ex instanceof Expression.Star);
	    if (any) return;
			    
	    if (ex instanceof Expression.BracketList) {
		for(Expression z: (Expression.BracketList)ex) {
		    addAll( new PositionList(z, orders));
		}
	    } else if (ex instanceof Expression.Num) {
		list1.add( ((Expression.Num)ex).nVal);
	    } else if (ex instanceof Expression.Id) {
		String s = ((Expression.Id)ex).sVal;
		// validate as the name of a predefined or custom order 
		if (!orders.containsKey(s)) {
		    if (Order.predefinedOrders.containsKey(s)) {
			orders.put( s, (Order.predefinedOrders.get(s)));
		    }
	
		}
		list2.add(s);		       
	    } else {
		throw new RuleParseException("Invalid position specifier: " + ex);
	    }
	}

	/** Does this position list presently allow picking a piece from the
	    specified position?
	    @param  eligibleForEachOrder What positions are now "in front"
	    of each order */
	boolean allowsPicking(int pos,
			      HashMap<String, BitSet> eligibleForEachOrder) {
	    for(int k: list1) {
		if (k==pos) return true;
	    }
	    for(String orderName: list2) {
		BitSet eligible =  eligibleForEachOrder.get(orderName);
		if ( eligible == null) throw new IllegalArgumentException("Unknown order name ("+orderName+") - should have been caught before!");
		if (eligible.get(pos)) return true;
	    }
	    return false;
	}
	
    }

    static class BucketList extends Vector<Expression.ArithmeticExpression>{
	BucketList( Expression.BracketList bex) throws RuleParseException {
	    for(Expression ex: bex) {
		if (!(ex instanceof Expression.ArithmeticExpression)) 	throw new RuleParseException("Invalid bucket specifier (not a symbol or an arithmetic expression): " + ex);
		
		Expression.ArithmeticExpression g = (Expression.ArithmeticExpression)ex;

		add(g);
	    }
	}

	public String toString() {
	    return "[" + Util.joinNonBlank(",",this)  + "]";	       
	}

	/** To which destinations a piece can be taken?
	    @param varMap Information about p, ps, pc, Nearby etc for the piece
	    under consideration*/
	BitSet destinations( HashMap<String, HashSet<Integer>> varMap) {
	    BitSet q= new BitSet(Board.buckets.length);
	    for(Expression.ArithmeticExpression ae: this) {
		q.or( Util.toBitSet( ae.evalSet(varMap)));
	    }
	    return q;
	}
	
    }

    
    /** Syntax:(counter,shape,color,position,bucketFunctions)
	<p>
	Example: (10,square,*,*,[1,2]) (10,*,blue,10,[2,3])
    */
    static class Atom {
	/** -1 means "no limit" */
	final int counter;
	/** null means "no restriction" */
	final Piece.Shape shape;
	//final
	Piece.Color color;
	PositionList plist;       	
	BucketList bucketList;


	public String toString() {
	    return "(" + counter + ","  +shape + "," + color+","+
		plist +  "," + bucketList + ")";	       
	}

	
	/** Syntax:(counter,shape,color,position,bucketFunctions)     */
	Atom(Expression.ParenList pex, TreeMap<String, Order> orders) throws RuleParseException {
	    if (pex.size()!=5)  throw new RuleParseException("Expected a tuple with 5 elements; found "+ pex.size() +": " + pex);

	    Expression g = pex.get(0); // count
	    if (g instanceof Expression.Star) {
		counter = -1;
	    } else if (g instanceof Expression.Num) {
		counter = ((Expression.Num)g).nVal;
	    } else {
		throw new RuleParseException("Counter is not a star or number: " + pex);
	    }

	    g = pex.get(1); // shape
	    if (g instanceof Expression.Star) {
		shape = null;
	    } else if (g instanceof Expression.Id) {
		String s = ((Expression.Id)g).sVal;
		shape = (Piece.Shape)Enum.valueOf(Piece.Shape.class, s.toUpperCase());
	    } else  {
		throw new RuleParseException("Invalid shape ("+g+") in: " + pex);
	    }
	    g = pex.get(2); // color
	    if (g instanceof Expression.Star) {
		color = null;
	    } else if (g instanceof Expression.Id) {
		String s = ((Expression.Id)g).sVal;
		color = (Piece.Color)Enum.valueOf(Piece.Color.class, s.toUpperCase());
	    } else  {
		throw new RuleParseException("Invalid color ("+g+") in: " + pex);
	    }
	    g = pex.get(3); // position
	    plist = new PositionList(g, orders);
	    g = pex.get(4); // buckets
	    if (g instanceof Expression.BracketList) {
		bucketList = new BucketList((Expression.BracketList)g);
	    } else {
		throw new RuleParseException("Buckets must be specified by a bracket list. Instead, found "+g+" in: " + pex);
	    }
		      
	}
	
    }

    
    /** A row object represents the content of one line of the rule set
	description file, i.e. the optional global counter and 
	one or several rules */
    static class Row extends Vector<Atom> {
	final int globalCounter;
	Row(Vector<Token> tokens, TreeMap<String, Order> orders)
	    throws RuleParseException {
	    
	    int gc=-1;
	    boolean first=true;
	    
	    // fixme: keep using ex!
	    while(tokens.size()>0) {
		Expression ex = Expression.mkExpression(tokens);

		if (first) {
		    first=false;
		    if (ex instanceof Expression.Num) {
			gc = ((Expression.Num)ex).nVal;
			continue;
		    } 
		}
		
		if (!(ex instanceof Expression.ParenList)) throw new RuleParseException("Expected a paren list; found " + ex);
		Expression.ParenList pex = (Expression.ParenList)ex;
		add(new Atom(pex, orders));		    
	    }

	    globalCounter= gc;
	}
	
	public String toString() {
	    return "(globalCounter="+globalCounter+") "+Util.joinNonBlank(" ",this);	       
	}
    }

    /** All orders */
    TreeMap<String, Order> orders =  new TreeMap<>();
    /** All rows of this rule set */
    Vector<Row> rows = new Vector<>();
    
    public RuleSet(String ruleText) throws RuleParseException {
	this( ruleText.split("\n"));
    }

    public RuleSet(String[] rr) throws RuleParseException {
	for(String r: rr) {
	    r = r.trim();
	    if (r.startsWith("#") || r.length()==0) continue;


	    Vector<Token> tokens= Token.tokenize(r);
	    if (tokens.size()==0) throw new RuleParseException("No data found in the line: " + r);
	    if (rows.size()==0 && tokens.get(0).type==Token.Type.ID &&
		tokens.get(0).sVal.equalsIgnoreCase("Order")) { // order line
		tokens.remove(0);
		if (tokens.size()==0 || tokens.get(0).type!=Token.Type.ID )  throw new RuleParseException("Missing order name in an order line: " + r);
		String name = tokens.get(0).sVal;
		tokens.remove(0);
		if (tokens.size()==0 ||  tokens.get(0).type!=Token.Type.EQUAL)  throw new RuleParseException("Missing equal sign in an order line: " + r);
		tokens.remove(0);
		Expression ex = Expression.mkExpression(tokens);
		if (!(ex instanceof Expression.BracketList) || tokens.size()>0) throw new RuleParseException("Invalid order description: " +r);	
		orders.put(name, new Order((Expression.BracketList)ex));
		continue;
	    }
		
	    Row row = new Row(tokens, orders);
	    if (row.size()==0)  throw new RuleParseException("No rules found in this line: "+r);
	    rows.add(row);

	}
	if (rows.size()==0) throw new RuleParseException("The rule set contains no rules");
    }

    public String toString() {
	Vector<String> v = new Vector<>();
	for(String name: orders.keySet()) {
	    v.add("Order " + name + "=" + orders.get(name));
	}
	String s = String.join("\n", v);
	if (v.size()>0) s+="\n";
	return s +    Util.joinNonBlank("\n",rows);	       
    }

    
    
    public static void main(String[] argv) throws IOException,  RuleParseException {
	System.out.println("Have " + argv.length + " files to read");
	for(String a: argv) {
	    File f = new File(a);
	    System.out.println("Reading file " + f);
	    String text = Util.readTextFile(f);
	    RuleSet rules = new RuleSet(text);
	    System.out.println(rules);
	}

    }
}