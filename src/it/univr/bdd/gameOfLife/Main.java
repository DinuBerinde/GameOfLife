package it.univr.bdd.gameOfLife;


public class Main {

	public static void main(String[] args) {
		new GliderGameOfLife(10, 1).begin();
		new BlinkerGameOfLife(10, 0).begin();
	}

}
