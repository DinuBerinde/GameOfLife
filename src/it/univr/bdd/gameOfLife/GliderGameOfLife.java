package it.univr.bdd.gameOfLife;

import com.juliasoft.beedeedee.bdd.BDD;

public class GliderGameOfLife extends AbstractGameOfLife{

	public GliderGameOfLife(int dimension, int type) {
		super(dimension, type);
	}

	/**
	 * This is the initial board configuration with the glider in the center of the board.
	 */
	@Override
	public BDD buildInitialGeneration() {
		BDD res = factory.makeOne();

		// set glider in the center of the board
		res.andWith(board[5][3].copy());  
		res.andWith(board[5][4].copy());  
		res.andWith(board[5][5].copy());  
		res.andWith(board[4][5].copy());  
		res.andWith(board[3][4].copy());  

		
		for(int i = 0; i < this.dimension; i++)
			for(int j = 0; j < this.dimension; j++){

				if((i != 5 || j != 3) && (i != 5 || j != 4) && (i != 5 || j != 5)
						&& (i != 4 || j != 5) && (i != 3 || j != 4)){

					res.andWith(board[i][j].not()); // place the dead cell 
				} 
			}

		
		return res;
	}


}
