# Goal

Check pairing heap implementation for concurrency bugs

# Possible bugs

- value lost
- double value
- value out of order
- exception

# Testing methodology

- generate a plan of actions, e.g.  insert insert insert accept insert insert accept
  - either we have to ensure there was an insert before accept
  - or accept will be best-effort - checks for readiness, if not then noop
- assign actions to 2 threads, e.g. t1     t2     t1     t2     t1     t2     t1 
- execute the plan
- on accept we dequeue all values and check for inconsistencies
- when threads settled (we join them) we do a final accept and check (or noop if not ready)
- finally we check if we collected all values

# Plan execution options

- we write our own executioner first
- we will investigate if we could use lincheck with out executioner afterwards - model checking would prove more

# On a concurrency bug

- we print the plan causing it 
- we might be able to brute force it again
- or we try to force event ordering (thread sleeps?)

