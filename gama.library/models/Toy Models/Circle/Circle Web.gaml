/**
* Name: Circle Web
* Description: Circle model for GAMA Web browser rendering
*/
model circle_web

global { 
	int number_of_agents min: 1 <- 50;
	int radius_of_circle min: 10 <- 1000;
	int repulsion_strength min: 1 <- 5;
	int width_and_height_of_environment min: 10 <- 3000; 
	int range_of_agents min: 1 <- 25;
	float speed_of_agents min: 0.1  <- 2.0; 
	int size_of_agents <- 100;
	point center const: true <- {width_and_height_of_environment/2,width_and_height_of_environment/2};
	geometry shape <- square(width_and_height_of_environment);
	init { 
		create cell number: number_of_agents;
	}  
}  
  
species cell skills: [moving] {  
	rgb color const: true <- [100 + rnd (155),100 + rnd (155), 100 + rnd (155)] as rgb;
	float cell_size const: true <- float(size_of_agents);
	float cell_range const: true <- float(range_of_agents); 
	float cell_speed const: true <- speed_of_agents;   
	float cell_heading <- rnd(360.0);
	
	reflex go_to_center {
		cell_heading <- (((self distance_to center) > radius_of_circle) ? self towards center : (self towards center) - 180);
		do move (speed: cell_speed); 
	}
	reflex flee_others {
		cell close <- one_of ( ( (self neighbors_at cell_range) of_species cell) sort_by (self distance_to each) );
		if close != nil {
			cell_heading <- (self towards close) - 180;
			float dist <- self distance_to close;
			do move (speed: dist / repulsion_strength, heading: cell_heading);
		}
	}
	
	aspect default { 
		draw circle(cell_size) color: color;
	}
}

experiment main type: gui {
	parameter "Size of Agents" var: size_of_agents <- 100;
	parameter 'Number of Agents' var: number_of_agents <- 50;
	output {
		display Circle type:2d {
			species cell;
		}
	}
}
