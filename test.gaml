model test_sir

global {
    int nb_infected <- 1;
    int nb_recovered <- 0;
    int nb_susceptible <- 99;
    
    action infect {
        nb_infected <- nb_infected + 1;
        nb_susceptible <- nb_susceptible - 1;
    }
}

experiment main {
    reflex step {
        ask world { do infect; }
    }
}