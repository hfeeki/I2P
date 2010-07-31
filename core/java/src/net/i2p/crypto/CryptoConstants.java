package net.i2p.crypto;

/* 
 * Copyright (c) 2003, TheCrypto
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this 
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * -  Neither the name of the TheCrypto may be used to endorse or promote 
 *    products derived from this software without specific prior written 
 *    permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

/**
 * Prime for ElGamal from http://tools.ietf.org/html/rfc3526
 * Primes for DSA: unknown.
 */
public class CryptoConstants {
    public static final BigInteger dsap = new NativeBigInteger(
                                                               "9c05b2aa960d9b97b8931963c9cc9e8c3026e9b8ed92fad0a69cc886d5bf8015fcadae31"
                                                                                                                                                                                                                                                            + "a0ad18fab3f01b00a358de237655c4964afaa2b337e96ad316b9fb1cc564b5aec5b69a9f"
                                                                                                                                                                                                                                                            + "f6c3e4548707fef8503d91dd8602e867e6d35d2235c1869ce2479c3b9d5401de04e0727f"
                                                                                                                                                                                                                                                            + "b33d6511285d4cf29538d9e3b6051f5b22cc1c93",
                                                               16);
    public static final BigInteger dsaq = new NativeBigInteger("a5dfc28fef4ca1e286744cd8eed9d29d684046b7", 16);
    public static final BigInteger dsag = new NativeBigInteger(
                                                               "c1f4d27d40093b429e962d7223824e0bbc47e7c832a39236fc683af84889581075ff9082"
                                                                                                                                                                                                                                                            + "ed32353d4374d7301cda1d23c431f4698599dda02451824ff369752593647cc3ddc197de"
                                                                                                                                                                                                                                                            + "985e43d136cdcfc6bd5409cd2f450821142a5e6f8eb1c3ab5d0484b8129fcf17bce4f7f3"
                                                                                                                                                                                                                                                            + "3321c3cb3dbb14a905e7b2b3e93be4708cbcc82",
                                                               16);
    public static final BigInteger elgp = new NativeBigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
                                                               + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
                                                               + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
                                                               + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                                                               + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
                                                               + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
                                                               + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
                                                               + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                                                               + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
                                                               + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
                                                               + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    public static final BigInteger elgg = new NativeBigInteger("2");
}
