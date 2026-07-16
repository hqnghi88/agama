model SimpleTest

global {
	int nb_agents <- 20;
	
	init {
		create test_agent number: nb_agents;
	}
}

species test_agent skills: [moving] {
	geometry shape <- circle(3.0);
	point location <- any_point_in(world.shape);
	
	reflex move {
		do wander;
	}
	
	aspect default {
		draw shape color: rgb(0, 0, 255);
	}
}

experiment test_experiment type: gui {
	output {
		display map type: 2d {
			species test_agent;
		}
	}
}
