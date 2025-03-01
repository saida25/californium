/*******************************************************************************
 * Copyright (c) 2015, 2016 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Martin Lanter - architect and re-implementation
 *    Dominique Im Obersteg - parsers and initial implementation
 *    Daniel Pauli - parsers and initial implementation
 *    Kai Hudalla - logging
 *    Bosch Software Innovations GmbH - reduce code duplication, split up into
 *                                      separate test cases, remove wait cycles
 ******************************************************************************/
package org.eclipse.californium.core.test.lockstep;

import static org.eclipse.californium.core.coap.CoAP.Code.GET;
import static org.eclipse.californium.core.coap.CoAP.Code.POST;
import static org.eclipse.californium.core.coap.CoAP.Code.PUT;
import static org.eclipse.californium.core.coap.OptionNumberRegistry.OBSERVE;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CHANGED;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTINUE;
import static org.eclipse.californium.core.coap.CoAP.Type.ACK;
import static org.eclipse.californium.core.coap.CoAP.Type.CON;
import static org.eclipse.californium.core.test.lockstep.IntegrationTestTools.*;
import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.eclipse.californium.category.Medium;
import org.eclipse.californium.core.coap.BlockOption;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;


/**
 * This test implements all examples from the blockwise draft 14 for a client.
 */
@Category(Medium.class)
public class BlockwiseClientSideTest {

	private static NetworkConfig CONFIG;

	private LockstepEndpoint server;
	private Endpoint client;
	private int mid = 8000;
	private String respPayload;
	private String reqtPayload;
	private ClientBlockwiseInterceptor clientInterceptor = new ClientBlockwiseInterceptor();

	@BeforeClass
	public static void init() {
		System.out.println(System.lineSeparator() + "Start " + BlockwiseClientSideTest.class.getSimpleName());
		CONFIG = new NetworkConfig()
				.setInt(NetworkConfig.Keys.MAX_MESSAGE_SIZE, 128)
				.setInt(NetworkConfig.Keys.PREFERRED_BLOCK_SIZE, 128)
				.setInt(NetworkConfig.Keys.ACK_TIMEOUT, 200) // client retransmits after 200 ms
				.setInt(NetworkConfig.Keys.ACK_RANDOM_FACTOR, 1);
	}

	@Before
	public void setupEndpoints() throws Exception {

		client = new CoapEndpoint(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), CONFIG);
		client.addInterceptor(clientInterceptor);
		client.start();
		System.out.println("Client binds to port " + client.getAddress().getPort());
		server = createLockstepEndpoint(client.getAddress());
	}

	@After
	public void shutdownEndpoints() {
		client.destroy();
		server.destroy();
	}

	@AfterClass
	public static void end() {
		System.out.println("End " + BlockwiseClientSideTest.class.getSimpleName());
	}

	/**
	 * The first example shows a GET request that is split into three blocks.
	 * The server proposes a block size of 128, and the client agrees. The first
	 * two ACKs contain 128 bytes of payload each, and third ACK contains
	 * between 1 and 128 bytes.
	 * <pre>
	 * CLIENT                                                     SERVER
     * |                                                            |
     * | CON [MID=1234], GET, /status                       ------> |
     * |                                                            |
     * | <------   ACK [MID=1234], 2.05 Content, 2:0/1/128          |
     * |                                                            |
     * | CON [MID=1235], GET, /status, 2:1/0/128            ------> |
     * |                                                            |
     * | <------   ACK [MID=1235], 2.05 Content, 2:1/1/128          |
     * |                                                            |
     * | CON [MID=1236], GET, /status, 2:2/0/128            ------> |
     * |                                                            |
     * | <------   ACK [MID=1236], 2.05 Content, 2:2/0/128          |
     * </pre>
	 */
	@Test
	public void testGET() throws Exception {
		System.out.println("Simple blockwise GET:");
		respPayload = generateRandomPayload(300);
		String path = "test";

		Request request = createRequest(GET, path, server);
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeBoth("A").go();
		server.sendResponse(ACK, CONTENT).loadBoth("A").block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();
		server.expectRequest(CON, GET, path).storeBoth("C").block2(2, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, false, 128).payload(respPayload.substring(256, 300)).go();

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, respPayload);

		printServerLog(clientInterceptor);
	}

	// TODO: what is the purpose of this test?
	@Test
	public void testGET2() throws Exception {
		System.out.println("Simple blockwise GET:");
		respPayload = generateRandomPayload(10);
		String path = "test";

		Request request = createRequest(GET, path, server);
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeMID("A").storeToken("B").go();
		server.sendEmpty(ACK).loadMID("A").go();
		Thread.sleep(50);
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload).mid(++mid).go();
		server.expectEmpty(ACK, mid).mid(mid).go(); // lost
		clientInterceptor.log(" // lost");
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload).mid(mid).go();
		server.expectEmpty(ACK, mid).mid(mid).go(); // lost

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, respPayload);

		printServerLog(clientInterceptor);
	}

	/**
	 * In the second example, the client anticipates the blockwise transfer
	 * (e.g., because of a size indication in the link- format description
	 * [RFC6690]) and sends a size proposal. All ACK messages except for the
	 * last carry 64 bytes of payload; the last one carries between 1 and 64
	 * bytes.
	 * <pre>
	 * CLIENT                                                     SERVER
	 * |                                                          |
	 * | CON [MID=1234], GET, /status, 2:0/0/64           ------> |
	 * |                                                          |
	 * | <------   ACK [MID=1234], 2.05 Content, 2:0/1/64         |
	 * |                                                          |
	 * | CON [MID=1235], GET, /status, 2:1/0/64           ------> |
	 * |                                                          |
	 * | <------   ACK [MID=1235], 2.05 Content, 2:1/1/64         |
	 * :                                                          :
	 * :                          ...                             :
	 * :                                                          :
	 * | CON [MID=1238], GET, /status, 2:4/0/64           ------> |
	 * |                                                          |
	 * | <------   ACK [MID=1238], 2.05 Content, 2:4/1/64         |
	 * |                                                          |
	 * | CON [MID=1239], GET, /status, 2:5/0/64           ------> |
	 * |                                                          |
	 * | <------   ACK [MID=1239], 2.05 Content, 2:5/0/64         |
	 * </pre>
	 */
	@Test
	public void testGETEarlyNegotion() throws Exception {
		System.out.println("Blockwise GET with early negotiation: (low priority for Cf client)");
		respPayload = generateRandomPayload(350);
		String path = "test";

		Request request = createRequest(GET, path, server);
		request.getOptions().setBlock2(BlockOption.size2Szx(64), false, 0);
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeBoth("A").block2(0, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("A").block2(0, true, 64).payload(respPayload.substring(0, 64)).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 64).payload(respPayload.substring(64, 128)).go();
		server.expectRequest(CON, GET, path).storeBoth("C").block2(2, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, true, 64).payload(respPayload.substring(128, 192)).go();
		server.expectRequest(CON, GET, path).storeBoth("D").block2(3, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("D").block2(3, true, 64).payload(respPayload.substring(192, 256)).go();
		server.expectRequest(CON, GET, path).storeBoth("E").block2(4, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("E").block2(4, true, 64).payload(respPayload.substring(256, 320)).go();
		server.expectRequest(CON, GET, path).storeBoth("F").block2(5, false, 64).go();
		server.sendResponse(ACK, CONTENT).loadBoth("F").block2(5, false, 64).payload(respPayload.substring(320, 350)).go();

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, respPayload);

		printServerLog(clientInterceptor);
	}

	/**
	 * <pre>
	 * CLIENT                                                     SERVER
     * |                                                          |
     * | CON [MID=1234], GET, /status                     ------> |
     * |                                                          |
     * | <------   ACK [MID=1234], 2.05 Content, 2:0/1/128        |
     * |                                                          |
     * | CON [MID=1235], GET, /status, 2:2/0/64           ------> |
     * |                                                          |
     * | //////////////////////////////////tent, 2:2/1/64         |
     * |                                                          |
     * | (timeout)                                                |
     * |                                                          |
     * | CON [MID=1235], GET, /status, 2:2/0/64           ------> |
     * |                                                          |
     * | <------   ACK [MID=1235], 2.05 Content, 2:2/1/64         |
     * :                                                          :
     * :                          ...                             :
     * :                                                          :
     * | CON [MID=1238], GET, /status, 2:5/0/64           ------> |
     * |                                                          |
     * | <------   ACK [MID=1238], 2.05 Content, 2:5/0/64         |
     * </pre>
	 */
	@Test
	public void testGETLateNegotionAndLostACK() throws Exception {
		System.out.println("Blockwise GET with late negotiation and lost ACK:");
		respPayload = generateRandomPayload(300);
		String path = "test";

		Request request = createRequest(GET, path, server);
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeBoth("A").go();
		server.sendResponse(ACK, CONTENT).loadBoth("A").block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 128).go();

		// We lose this ACK, and therefore the client retransmits the CON
		clientInterceptor.log(" // lost");
		server.expectRequest(CON, GET, path).storeBoth("C").block2(1, false, 128).go();
		Object[] req1 = (Object[]) server.get("B");
		Object[] req2 = (Object[]) server.get("C");
		assertEquals("Retransmitted MID must be the same", req1[0], req2[0]);
		assertArrayEquals(
				"Retransmitted Token must be the same", (byte[]) req1[1], (byte[]) req2[1]);

		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();
		server.expectRequest(CON, GET, path).storeBoth("D").block2(2, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("D").block2(2, false, 128).payload(respPayload.substring(256, 300)).go();

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, respPayload);

		printServerLog(clientInterceptor);
	}

	/**
	 * The following examples demonstrate a PUT exchange; a POST exchange looks
	 * the same, with different requirements on atomicity/idempotence. Note
	 * that, similar to GET, the responses to the requests that have a more bit
	 * in the request Block1 Option are provisional and carry the response code
	 * 2.31 (Continue); only the final response tells the client that the PUT
	 * did succeed.
	 * <pre>
	 * CLIENT                                                     SERVER
     * |                                                          |
     * | CON [MID=1234], PUT, /options, 1:0/1/128    ------>      |
     * |                                                          |
     * | <------   ACK [MID=1234], 2.31 Continue, 1:0/1/128       |
     * |                                                          |
     * | CON [MID=1235], PUT, /options, 1:1/1/128    ------>      |
     * |                                                          |
     * | <------   ACK [MID=1235], 2.31 Continue, 1:1/1/128       |
     * |                                                          |
     * | CON [MID=1236], PUT, /options, 1:2/0/128    ------>      |
     * |                                                          |
     * | <------   ACK [MID=1236], 2.04 Changed, 1:2/0/128        |
	 * </pre>
	 */
	@Test
	public void testSimpleAtomicBlockwisePUT() throws Exception {
		System.out.println("Simple atomic blockwise PUT");
		reqtPayload = generateRandomPayload(300);
		respPayload = generateRandomPayload(50);
		String path = "test";

		Request request = createRequest(PUT, path, server);
		request.setPayload(reqtPayload);
		client.sendRequest(request);

		server.expectRequest(CON, PUT, path).storeBoth("A").block1(0, true, 128).payload(reqtPayload.substring(0, 128)).go();
		server.sendResponse(ACK, CONTINUE).loadBoth("A").block1(0, true, 128).go();

		server.expectRequest(CON, PUT, path).storeBoth("B").block1(1, true, 128).payload(reqtPayload.substring(128, 256)).go();
		server.sendResponse(ACK, CONTINUE).loadBoth("B").block1(1, true, 128).go();

		server.expectRequest(CON, PUT, path).storeBoth("C").block1(2, false, 128).go();
		server.sendResponse(ACK, CHANGED).loadBoth("C").block1(2, false, 128).payload(respPayload).go();	

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, CHANGED, respPayload);

		printServerLog(clientInterceptor);
	}

	/**
	 * Block options may be used in both directions of a single exchange. The
	 * following example demonstrates a blockwise POST request, resulting in a
	 * separate blockwise response.
	 * <pre>
	 * CLIENT                                                     SERVER
     * |                                                              |
     * | CON [MID=1234], POST, /soap, 1:0/1/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1234], 2.31 Continue, 1:0/1/128           |
     * |                                                              |
     * | CON [MID=1235], POST, /soap, 1:1/1/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1235], 2.31 Continue, 1:1/1/128           |
     * |                                                              |
     * | CON [MID=1236], POST, /soap, 1:2/0/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1236], 2.04 Changed, 2:0/1/128, 1:2/0/128 |
     * |                                                              |
     * | CON [MID=1237], POST, /soap, 2:1/0/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1237], 2.04 Changed, 2:1/1/128            |
     * |                                                              |
     * | CON [MID=1238], POST, /soap, 2:2/0/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1238], 2.04 Changed, 2:2/1/128            |
     * |                                                              |
     * | CON [MID=1239], POST, /soap, 2:3/0/128      ------>          |
     * |                                                              |
     * | <------   ACK [MID=1239], 2.04 Changed, 2:3/0/128            |
	 * </pre>
	 */
	@Test
	public void testAtomicBlockwisePOSTWithBlockwiseResponse() throws Exception {
		System.out.println("Atomic blockwise POST with blockwise response:");
		reqtPayload = generateRandomPayload(300);
		respPayload = generateRandomPayload(500);
		String path = "test";

		Request request = createRequest(POST, path, server);
		request.setPayload(reqtPayload);
		client.sendRequest(request);

		server.expectRequest(CON, POST, path).storeBoth("A").block1(0, true, 128).payload(reqtPayload.substring(0, 128)).go();
		server.sendResponse(ACK, CONTINUE).loadBoth("A").block1(0, true, 128).go();

		server.expectRequest(CON, POST, path).storeBoth("B").block1(1, true, 128).payload(reqtPayload.substring(128, 256)).go();
		server.sendResponse(ACK, CONTINUE).loadBoth("B").block1(1, true, 128).go();

		server.expectRequest(CON, POST, path).storeBoth("C").block1(2, false, 128).payload(reqtPayload.substring(256, 300)).go();
		server.sendResponse(ACK, CHANGED).loadBoth("C").block1(2, false, 128).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();

		server.expectRequest(CON, POST, path).storeBoth("D").block2(1, false, 128).go();
		server.sendResponse(ACK, CHANGED).loadBoth("D").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();

		server.expectRequest(CON, POST, path).storeBoth("E").block2(2, false, 128).go();
		server.sendResponse(ACK, CHANGED).loadBoth("E").block2(2, true, 128).payload(respPayload.substring(256, 384)).go();

		server.expectRequest(CON, POST, path).storeBoth("F").block2(3, false, 128).go();
		server.sendResponse(ACK, CHANGED).loadBoth("F").block2(3, false, 128).payload(respPayload.substring(384, 500)).go();

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, CHANGED, respPayload);

		printServerLog(clientInterceptor);
	}

	@Test
	public void testRandomAccessGET() throws Exception {
		System.out.println("TODO: Random access GET: (low priority for Cf client)");
		// TODO: has low priority
	}

	@Test
	public void testObserveWithBlockwiseResponse() throws Exception {
		System.out.println("Observe sequence with blockwise response:");
		respPayload = generateRandomPayload(300);
		String path = "test1";

		Request request = createRequest(GET, path, server);
		request.setObserve();
		client.sendRequest(request);

		System.out.println("Establish observe relation to " + path);

		server.expectRequest(CON, GET, path).storeToken("At").storeMID("Am").observe(0).go();
		server.sendResponse(ACK, CONTENT).loadToken("At").loadMID("Am").observe(62350).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();

		server.expectRequest(CON, GET, path).storeBoth("B").noOption(OBSERVE).block2(1, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();

		// TODO: Is is mandatory that token C is the same as B?
		server.expectRequest(CON, GET, path).storeBoth("C").noOption(OBSERVE).block2(2, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, false, 128).payload(respPayload.substring(256, 300)).go();

		Response response = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, respPayload);

		System.out.println("Server sends first notification...");
		clientInterceptor.log(System.lineSeparator() + "... time passes ...");
		respPayload = generateRandomPayload(280);

		server.sendResponse(CON, CONTENT).loadToken("At").mid(++mid).observe(62354).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectEmpty(ACK, mid).go();

		server.expectRequest(CON, GET, path).storeBoth("D").noOption(OBSERVE).block2(1, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("D").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();

		server.expectRequest(CON, GET, path).storeBoth("E").noOption(OBSERVE).block2(2, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("E").block2(2, false, 128).payload(respPayload.substring(256, 280)).go();

		Response notification1 = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(notification1, respPayload);

		System.out.println("Server sends second notification...");
		clientInterceptor.log(System.lineSeparator() + "... time passes ...");
		respPayload = generateRandomPayload(290);

		server.sendResponse(CON, CONTENT).loadToken("At").mid(++mid).observe(17).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectEmpty(ACK, mid).go();

		// expect blockwise transfer for second notification
		server.expectRequest(CON, GET, path).storeBoth("F").noOption(OBSERVE).block2(1, false, 128).go();

		System.out.println("Server sends third notification during transfer ");
		server.sendResponse(CON, CONTENT).loadToken("At").mid(++mid).observe(19).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectEmpty(ACK, mid).go();

		// expect blockwise transfer for third notification
		server.expectRequest(CON, GET, path).storeBoth("G").noOption(OBSERVE).block2(1, false, 128).go();

		System.out.println("Send old notification during transfer");
		server.sendResponse(CON, CONTENT).loadToken("At").mid(++mid).observe(18).block2(0, true, 128).payload(respPayload.substring(0, 128)).go();
		server.expectEmpty(ACK, mid).go();

		server.sendResponse(ACK, CONTENT).loadBoth("G").block2(1, true, 128).payload(respPayload.substring(128, 256)).go();

		server.expectRequest(CON, GET, path).storeBoth("H").noOption(OBSERVE).block2(2, false, 128).go();
		server.sendResponse(ACK, CONTENT).loadBoth("H").block2(2, false, 128).payload(respPayload.substring(256, 290)).go();

		Response notification2 = request.waitForResponse(1000);
		assertResponseContainsExpectedPayload(notification2, respPayload);

		printServerLog(clientInterceptor);
	}

	@Test
	public void testObserveWithBlockwiseResponseEarlyNegotiation() throws Exception {
		System.out.println("TODO: Observe sequence with early negotiation: (low priority for Cf client)");
		// TODO: This is not really a problem for the Cf client because it has
		// no block size preference. We might only allow a developer to enforce
		// a specific block size but this currently has low priority.
	}

}
