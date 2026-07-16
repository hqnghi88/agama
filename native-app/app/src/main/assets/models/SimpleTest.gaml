model SimpleTest

global {
	int nb_agents <- 20;
	
	init {
		create test_agent number: nb_agents;
	}
}

species test_agent {
	int age <- 0;
	
	reflex aging {
		age <- age + 1;
	}
	
	aspect default {
		draw circle(3.0) color: rgb(0, 0, 255);
	}
}

experiment test_experiment type: gui {
	output {
		monitor "Counter" value: cycle;
	}
}
