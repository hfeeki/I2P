jbigi.jar was built by jrandom on Aug 21, 2004 with the jbigi and jcpuid 
native libraries compiled on linux, winXP (w/ MinGW), and freebsd (4.8).  
The GMP code in jbigi is from GMP-4.1.3 (http://www.swox.com/gmp/), and 
was optimized for a variety of CPU architectures.

On Sep 16, 2005, libjbigi-osx-none.jnilib was added to jbigi.jar after
being compiled by jrandom on osx/ppc with GMP-4.1.4.

On Sep 18, 2005, libjbigi-linux-athlon64.so was added to jbigi.jar after
being compiled by jrandom on linux/p4 (cross compiled to --host=x86_64)
with GMP-4.1.4.

On Nov 29, 2005, the libjbigi-osx-none.jnilib was added back to
jbigi.jar after being mistakenly removed in the Sep 18 update (d'oh!)

On Dec 30, 2005, the libjcpuid-x86-linux.so was updated to use the 
(year old) C version of jcpuid, rather than the C++ version.  This removes
the libg++.so.5 dependency that has been a problem for a few linux distros.
