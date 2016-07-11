package it.univr.bdd.gameOfLife;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.juliasoft.beedeedee.bdd.Assignment;
import com.juliasoft.beedeedee.bdd.BDD;
import com.juliasoft.beedeedee.factories.Factory;

public abstract class AbstractGameOfLife {
	protected final BDD [][] board;
	protected final BDD [][] board_p;
	protected final int dimension;
	private final int type;
	private final int processors;
	private final int endGenerations;
	protected final Factory factory;
	private ArrayList<BDD> generations = new ArrayList<>();


	public AbstractGameOfLife(int dimension, int type){
		this.type = type;
		this.factory = Factory.mkResizingAndGarbageCollected(1000000, 100000);
		this.dimension = dimension;
		this.processors = Runtime.getRuntime().availableProcessors();
		this.board = new BDD[this.dimension][this.dimension];
		this.board_p = new BDD[this.dimension][this.dimension];

		// 1 - Glider, 0 - Blinker
		if(this.type == 1)
			this.endGenerations = this.dimension*2 + 1;
		else
			this.endGenerations = 2;

		buildVariables();
	}

	public abstract BDD buildInitialGeneration();

	public void begin(){
		long start = System.currentTimeMillis();
		System.out.println("***************** " + (this.type == 1 ? " Glider " : " Blinker ") + " example ***************************");
		
		BDD initial = buildInitialGeneration();
		buildGenerations(initial.copy());
		BDD preState = build_X();
		Map<Integer, Integer> renaming = buildRenaming();
		BDD transitions = build_T();
		BDD reachableStates = reachableStates(initial, transitions, preState, renaming);

		printSolution(reachableStates);

		initial.free();
		preState.free();
		transitions.free();
		reachableStates.free(); 

		factory.done();
		System.out.println("[*] Total time: " + (System.currentTimeMillis()-start));
		System.out.println("***************************************************************" + "\n" + "\n");
	}



	private void buildVariables(){
		for (int i = 0; i < this.dimension; i++){
			for (int j = 0; j < this.dimension; j++){
				board[i][j] = factory.makeVar(2 * (i * (this.dimension) + j));
				board_p[i][j] = factory.makeVar(2 * (i * (this.dimension) + j) + 1);
			}
		}		
	}


	/**
	 * Pre-state variables (variables before the transitions)
	 */
	private BDD build_X() {
		BDD res = factory.makeOne();

		for(int i = 0; i < this.dimension; i++){
			for(int j = 0; j < this.dimension; j++){
				res.andWith(board[i][j].copy());
			}
		}

		return res;
	}


	/**
	 * Post-state variables (variables after the transitions)
	 */
	private Map<Integer, Integer> buildRenaming() {
		Map<Integer, Integer> map = new HashMap<>();

		for(int i = 0; i < this.dimension; i++){
			for(int j = 0; j < this.dimension; j++)
				map.put(board_p[i][j].var(), board[i][j].var());
		}

		return map;
	}


	/**
	 * This is the reachable states algorithm.
	 * The algorithm always reaches a fix point, because R either satisfies I (initials), or within
	 * a finite number of T transitions can be reached from I.
	 */
	private BDD reachableStates(BDD i, BDD t, BDD x, Map<Integer, Integer> renaming){
		System.out.println("\n" + "[*] Computing reachable states" + "\n");

		BDD result = this.factory.makeZero();
		BDD rCopy = null;

		int counter = 1;
		do{

			System.out.println(" - iteration K = " + counter++);

			if(rCopy != null)
				rCopy.free();

			rCopy = result;	

			BDD and = t.and(result);			
			BDD exist = and.exist(x);
			and.free();

			BDD replace = exist.replace(renaming);
			result = i.or(replace);

			replace.free();

		}while(!result.isEquivalentTo(rCopy));

		rCopy.free();

		System.out.println("\n" + "[*] Done computing reachable states" + "\n");

		return result;
	}



	/**
	 * Transitions that the system can perform. A transition builds the next generation of cells.
	 * A transition is taken place on the current generation.
	 * 
	 * Idea: make transitions starting from the initial state (glider in the middle of the board),
	 * until it reaches the end of the game (when all cells died), which is equivalent to saying that all variables are false.
	 * During the computation, we perform the logical OR of the current transition with the others transitions.
	 *
	 * @return a BDD with all the transitions
	 */
	private BDD build_T() {
		ExecutorService executorService = Executors.newCachedThreadPool();
		List<Future<BDD>> tasks = new ArrayList<>(processors);

		int scheduler = processors;
		int work = endGenerations / processors;
		int half = work;

		if((endGenerations % processors) != 0)
			scheduler--;

		for (int i = 0; i < scheduler; i++){
			tasks.add(executorService.submit(new AsyncTask(work - half, work)));	

			work += half;
		}

		if((endGenerations % processors) != 0)
			tasks.add(executorService.submit(new AsyncTask(work - half, work+1)));	
		

		BDD res = factory.makeZero();
		for (int i = 0; i < processors; i++) {
			try {

				res.orWith(tasks.get(i).get());

			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}			
		}

		executorService.shutdown();

		return res;
	}

	/**
	 * Build a the list of BDD's corresponding to all generations
	 * @param initialGeneration the initial BDD generation
	 */
	private void buildGenerations(BDD initialGeneration){
		BDD currentGeneration = initialGeneration;
		int i = 0;
		
		this.generations.add(currentGeneration.copy());
	
		while(i++ < endGenerations){

			BDD nextGeneration = getNextGeneration(currentGeneration);
			this.generations.add(nextGeneration);
			
			currentGeneration.free();
			currentGeneration = nextGeneration.copy();
		}		
	} 

	private BDD buildTransitions(int start, int finish){
		BDD t = factory.makeZero();

		for (int i = start; i < finish; ++i){
			BDD transition = nextGeneration(this.generations.get(i));
			t.orWith(transition);
		}

		return t;
	}
	
	
	private final class AsyncTask implements Callable<BDD> {
		private final int start;
		private final int finish;

		public AsyncTask(int start, int finish) {
			this.start = start;
			this.finish = finish;
		}

		@Override
		public BDD call() throws Exception {
			return buildTransitions(start, finish);
		}
	}


	/**
	 * The rules of the next generation. This is one transition.
	 * A transition is a map from the BDD's of the matrix board to the BDD's of the matrix board_p,
	 * and all represented by a single resulting BDD.
	 * 
	 * Idea: we build the next generation based on the current generation represented by the argument BDD. 
	 * We iterate over the board matrix and when we encounter a live cell (when the assignment of the current variable holds),
	 * we try the rules: solitude, overpopulation, border.
	 * 
	 * Example: If the solitude rule holds at the cell (i,j)(the live cell is isolated, and should be removed), 
	 * the resulting BDD computes the logical AND with the BDD of board[i][j] and with the BDD of board_p[i]j].not(),
	 * this represents a map/transition.
	 * The same has to be done for all rules according to the case.
	 * 
	 * When we encounter a dead cell (when the assignment of the current variable doesn't hold), we try populating the cell.
	 * 
	 * @param generation the current generation
	 * @return a BDD which represents the next generation
	 */
	private BDD nextGeneration(BDD generation){
		BDD res = this.factory.makeOne();
		Assignment assignment = generation.anySat();


		// be build the map/transition from board to board_p vars
		for(int i = 0; i < this.dimension; i++)
			for(int j = 0; j < this.dimension; j++){


				// it's a live cell, we remove it if we can
				if(assignment.holds(this.board[i][j])){

					if(trySolitude(i, j, assignment)){	
						res.andWith(this.board[i][j].copy()); // evaluate T the var in board
						res.andWith(this.board_p[i][j].not()); // evaluate F the var in board_p						
					}

					else if(tryOverpopulation(i, j, assignment)){
						res.andWith(this.board[i][j].copy());  
						res.andWith(this.board_p[i][j].not()); 
					}

					else if(tryBorder(i, j)){
						res.andWith(this.board[i][j].copy());  
						res.andWith(this.board_p[i][j].not()); 
					}

					else{
						res.andWith(this.board[i][j].copy());  // evaluate T the var in board
						res.andWith(this.board_p[i][j].copy()); // evaluate T the var in board_p
					}

				}

				// it's a dead cell, we populate it if we can
				else{

					if(tryPopulatingDeadCell(i, j, assignment)){
						res.andWith(this.board[i][j].not()); 	// evaluate F the var in board
						res.andWith(this.board_p[i][j].copy()); // evaluate T the var in board_p
					}

					else
						res.andWith(this.board[i][j].biimp(this.board_p[i][j]));   // vars equal

				}
			}


		return res;
	}




	/**
	 * It returns the next generation based on the current generation
	 * 
	 * @param generation the current generation 
	 * @return a BDD which represents the next generation
	 */
	private BDD getNextGeneration(BDD generation){
		BDD res = this.factory.makeOne();
		Assignment assignment = generation.anySat();

		for(int i = 0; i < this.dimension; i++){
			for(int j = 0; j < this.dimension; j++){

				// it's a live cell, we remove it if we can
				if(assignment.holds(this.board[i][j])){

					if(trySolitude(i, j, assignment))	
						res.andWith(this.board[i][j].not()); // evaluate F

					else if(tryOverpopulation(i, j, assignment))
						res.andWith(this.board[i][j].not());  // evaluate F

					else if(tryBorder(i, j))
						res.andWith(this.board[i][j].not());  // evaluate F

					else
						res.andWith(this.board[i][j].copy());  // evaluate T

				}

				// it's a dead cell, we populate it if we can
				else{

					if(tryPopulatingDeadCell(i, j, assignment))
						res.andWith(this.board[i][j].copy()); 	// evaluate T	
					else
						res.andWith(this.board[i][j].not());   // evaluate F

				}
			}
		}

		return res;
	}



	/**
	 * Each live cell dies if it finds itself on the border of the board
	 * 
	 * @return true if it is on the border, false otherwise
	 */
	private boolean tryBorder(int x, int y){

		return y == this.dimension - 1 || x == this.dimension - 1;
	}


	/**
	 * Each live cell with one or no neighbors dies, as if by solitude
	 * 
	 * @return true if it's a solitude cell, false otherwise
	 */
	private boolean trySolitude(int x, int y, Assignment assignment){
		int neighbors = getNeighborCount(x, y, assignment);

		if(neighbors == 0 || neighbors == 1)
			return true;

		return false;
	}

	/**
	 * Each live cell with four or more neighbors dies, as if by overpopulation
	 *
	 * @return true if there is an overpopulation, false otherwise
	 */
	private boolean tryOverpopulation(int x, int y, Assignment assignment){

		if(getNeighborCount(x, y, assignment) >= 4)
			return true;

		return false;
	}


	/**
	 * Each dead cell with three neighbors becomes populated
	 * 
	 * @return true if the cell gets populated, false otherwise
	 */
	private boolean tryPopulatingDeadCell(int x, int y, Assignment assignment){

		if(getNeighborCount(x, y, assignment) == 3)
			return true;

		return false;
	}

	/**
	 * Get the number of neighbors that are positioned up, up-left, up-right, down, down-left, down-right, right, left.
	 *  
	 * @return the number of neighbors
	 */
	private int getNeighborCount(int x, int y, Assignment assignment){
		int neighbors = 0;

		// try neighbor up
		if(isNeighborAt(x-1, y, assignment))
			neighbors++;

		// try neighbor up-left
		if(isNeighborAt(x-1, y-1, assignment))
			neighbors++;

		// try neighbor up-right
		if(isNeighborAt(x-1, y+1, assignment))
			neighbors++;

		// try neighbor down
		if(isNeighborAt(x+1, y, assignment))
			neighbors++;

		// try neighbor down-left
		if(isNeighborAt(x+1, y-1, assignment))
			neighbors++;

		// try neighbor down-right
		if(isNeighborAt(x+1, y+1, assignment))
			neighbors++;

		// try neighbor left
		if(isNeighborAt(x, y-1, assignment))
			neighbors++;

		// try neighbor right
		if(isNeighborAt(x, y+1, assignment))
			neighbors++;

		return neighbors;
	}

	/**
	 * Check if a neighbor (live cell) is placed at (x,y) in the board matrix.
	 * To do that we check if the assignment assignment holds the BBD at (x,y). 
	 * 
	 * @param x position of the cell at x
	 * @param y position of the cell at y
	 * @param assignment the assignment of the current generation
	 * @return true if a live cell is at (x,y) in the board, false otherwise.
	 */
	private boolean isNeighborAt(int x, int y, Assignment assignment){
		if(x >= 0 && x < this.dimension && y >= 0 && y < this.dimension)
			if(assignment.holds(this.board[x][y]))
				return true;

		return false;
	}
	
	
	/**
	 * It builds a BDD representing the end of the game. The BDD will be the following predicate:
	 * P = (not X11) ^ (not X12) ^ ... ^ (not Xij), where X is the BDD of the cell at (i,j)
	 * 
	 * @return a BDD representing the end of the game.
	 */
	private BDD buildEndGame(){
		BDD res = factory.makeOne();

		for(int i = 0; i < this.dimension; i++){
			for(int j = 0; j < this.dimension; j++){
				res.andWith(board[i][j].not());
			}
		}
		
		return res;
	}
	
	/**
	 * We try to see if we can find a counter example.
	 * We build a BDD which is the logical AND between the reachable states BDD and the BDD representing
	 * the end of the game. Then we check the sat count number to see if we can find a satisfying assignment.
	 * 
	 * If the number is 0, this means that we couldn't find any satisfying assignment, hence we found that the reachable states
	 * BDD doesn't contains an assignment representing the end of the game, hence we found a counter example,
	 * which means that the game doesn't end. 
	 * 
	 * If the number is 1, we found a satisfying assignment, hence we found that the reachable states BDD contains an assignment
	 * representing the end of the game.
	 * 
	 * @param reachableStates the BDD of the reachable states 
	 * @return the number of sat assignments of the counter example BDD
	 */
	private boolean tryCounterExample(BDD reachableStates){
		BDD counterExample = reachableStates.andWith(buildEndGame());
		
		return counterExample.satCount(this.dimension * this.dimension - 1) == 0 ? true : false;
	}


	private void printSolution(BDD reachableStates){
		long solutions = reachableStates.satCount(this.dimension * this.dimension - 1);
		
		// we found a counter example
		if(tryCounterExample(reachableStates))
			System.out.println("[*] The system doesn't satisfy the properties, the game doesn't end because "
					+ "it doesn't reach the final state");
		else
			System.out.println("[*] The system satisfies the properties, the game ends because it reaches the final state");
		
	
		System.out.println("[*] Reachable states solutions: " + solutions);
		System.out.println("[*] The game ends after " + solutions + " generations/transitions");		
		System.out.println("[*] Reachable states algorithm always reaches a fix point");
	}


}

