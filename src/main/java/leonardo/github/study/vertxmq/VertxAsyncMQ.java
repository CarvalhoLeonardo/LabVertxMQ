package leonardo.github.study.vertxmq;


import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

/**
 * 
 * @author Leonardo T. de Carvalho
 * 
 * <a href="https://github.com/CarvalhoLeonardo">GitHub</a>
 * <a href="https://br.linkedin.com/in/leonardocarvalho">LinkedIn</a>
 *
 *	Based on a <a href="http://zguide.zeromq.org/java:asyncsrv">Zero MQ Example</a>
 *
 *	Using Verticles to run the Actors on the Example. 
 */

public class VertxAsyncMQ {
	static Vertx vertx = null;
	static final String address = "tcp://*:5558";
	static final String channel = "vertxmq";
	static Random rand = new Random(System.nanoTime());
	private final static Logger LOGGER = LoggerFactory.getLogger(VertxAsyncMQ.class);
	final static List<String> handlerIds = new ArrayList<String>();

	public class ClientTaskVert extends AbstractVerticle {

		private boolean keepRunning = false;

		@Override
		public void start() {
			keepRunning = true;
			ZContext ctx = new ZContext();
			Socket client = ctx.createSocket(ZMQ.DEALER);

			// Set random Identity to make tracing easier
			String identity = String.format("%04X-%04X", rand.nextInt(), rand.nextInt());
			client.setIdentity(identity.getBytes());
			client.connect(address);

			PollItem[] items = new PollItem[] { new PollItem(client, Poller.POLLIN) };

			int requestNbr = 0;
			while (keepRunning && ! Thread.currentThread().isInterrupted()) {
				// Tick once per second, pulling in arriving messages
				ZMQ.poll(items, 100);
				if (items[0].isReadable()) {
					ZMsg msg = ZMsg.recvMsg(client);
					msg.getLast().print(identity);
					msg.destroy();
				}
				client.send(String.format("request #%d", ++requestNbr), 0);
			}
			client.close();
			ctx.destroy();
		}

		@Override
		public void stop() throws Exception {
			LOGGER.debug("Stopping ClientTaskVert on thread " + Thread.currentThread().getName());
			keepRunning = false;
			Thread.currentThread().notify();
		}
	}

	// This is our server Verticle.
	// It uses the multithreaded server model to deal requests out to a pool
	// of workers and route replies back to clients. One worker can handle
	// one request at a time but one client can talk to multiple workers at
	// once.

	public class ServerVert extends AbstractVerticle {

		@Override
		public void start() {
			ZContext ctx = new ZContext();

			// Frontend socket talks to clients over TCP
			Socket frontend = ctx.createSocket(ZMQ.ROUTER);
			frontend.bind(address);

			// Backend socket talks to workers over inproc
			Socket backend = ctx.createSocket(ZMQ.DEALER);
			backend.bind("inproc://backend");

			// Launch pool of worker threads, precise number is not critical
			for (int i = 0; i < 5; i++) {
				vertx.deployVerticle(new ServerWorkerVert(ctx), handler -> {
					if (handler.succeeded()) {
						LOGGER.debug("Deployed ServerWorkerVert with id " + handler.result());
						handlerIds.add(0, handler.result());
					}
					else {
						LOGGER.error("Deploy of ServerWorkerVert failed : " + handler.cause().getMessage());
						handler.cause().printStackTrace();
					}

				});

			}

			ZMQ.proxy(frontend, backend, null);
			ctx.destroy();
		}
	}

	// Each worker task works on one request at a time and sends a random number
	// of replies back, with random delays between replies:

	public class ServerWorkerVert extends AbstractVerticle {
		private ZContext privctx;

		private boolean keepRunning = false;

		public ServerWorkerVert(ZContext ctx) {
			this.privctx = ctx;
		}

		@Override
		public void start() {
			keepRunning = true;
			Socket worker = privctx.createSocket(ZMQ.DEALER);
			worker.connect("inproc://backend");

			while (keepRunning && ! Thread.currentThread().isInterrupted()) {
				// The DEALER socket gives us the address envelope and message
				ZMsg msg = ZMsg.recvMsg(worker);
				ZFrame address = msg.pop();
				ZFrame content = msg.pop();
				assert (content != null);
				msg.destroy();

				// Send 0..4 replies back
				int replies = rand.nextInt(5);
				for (int reply = 0; reply < replies; reply++) {
					// Sleep for some fraction of a second
					try {
						Thread.sleep(rand.nextInt(1000) + 1);
					}
					catch (InterruptedException e) {
					}
					address.send(worker, ZFrame.REUSE + ZFrame.MORE);
					content.send(worker, ZFrame.REUSE);
				}
				address.destroy();
				content.destroy();
			}
		}

		@Override
		public void stop() throws Exception {
			LOGGER.debug("Stopping ServerWorkerVert on thread " + Thread.currentThread().getName());
			keepRunning = false;
			Thread.currentThread().notify();
		}
	}

	public static void main(String[] args) throws Exception {
		ZContext ctx = new ZContext();

		vertx = Vertx.vertx(new VertxOptions().setMaxEventLoopExecuteTime(5000).setBlockedThreadCheckInterval(5000));

		VertxAsyncMQ mySelf = new VertxAsyncMQ();

		vertx.deployVerticle(mySelf.new ServerVert(), handler -> {
			if (handler.succeeded()) {
				LOGGER.debug("Deployed ServerVerticle with id " + handler.result());
				handlerIds.add(0, handler.result());
			}
			else {
				LOGGER.error("Deploy of ServerVerticle failed : " + handler.cause().getMessage());
				handler.cause().printStackTrace();
			}
		});

		for (int i = 0; i < 5; i++) {
			vertx.deployVerticle(mySelf.new ClientTaskVert(), handler -> {
				if (handler.succeeded()) {
					LOGGER.debug("Deployed ClientVerticle with id " + handler.result());
					handlerIds.add(0, handler.result());
				}
				else {
					LOGGER.error("Deploy of ClientVerticle failed : " + handler.cause().getMessage());
					handler.cause().printStackTrace();
				}
			});
		}
		
		vertx.setTimer(5000, handler -> {
			// Run for 5 seconds then quit
			for (String eeachId : handlerIds) {
				LOGGER.debug("Undeploying id " + eeachId);
				vertx.undeploy(eeachId);
			}
			vertx.close(closehandler -> {
				if (closehandler.succeeded()) {
					LOGGER.debug("Stoped VertX !");
				} else {
					LOGGER.debug("Stopping VertX failed : "+closehandler.cause().getMessage());
					closehandler.cause().printStackTrace();
				}
				ctx.close();
				ctx.destroy();
				mySelf.notify();	
			});
			
		});

	}
}
