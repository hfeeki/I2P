# wrapper script to run stasher node

# set this to the directory where you've installed stasher
stasherDir = "/path/to/my/stasher/dir"

import sys
sys.path.append(stasherDir)
import stasher
stasher.main()
