package com.zendesk.maxwell.producer;

import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.util.StoppableTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class MaxwellRedisProducer extends AbstractProducer implements StoppableTask {
	private static final Logger logger = LoggerFactory.getLogger(MaxwellRedisProducer.class);
	private final String channel;
	private final String listkey;
	private final String redistype;
	private final Jedis jedis;

	public MaxwellRedisProducer(MaxwellContext context, String redisPubChannel, String redisListKey, String redisType) {
		super(context);

		channel = redisPubChannel;
		listkey = redisListKey;
		redistype = redisType;

		jedis = new Jedis(context.getConfig().redisHost, context.getConfig().redisPort);
		jedis.connect();
		if (context.getConfig().redisAuth != null) {
			jedis.auth(context.getConfig().redisAuth);
		}
		if (context.getConfig().redisDatabase > 0) {
			jedis.select(context.getConfig().redisDatabase);
		}
	}

	@Override
	public void push(RowMap r) throws Exception {
		int cxErrors = 0;
		if ( !r.shouldOutput(outputConfig) ) {
			context.setPosition(r.getNextPosition());
			return;
		}

		String msg = r.toJSON(outputConfig);
		while ( true ) {
			try {
				switch (redistype) {
					case "lpush":
						jedis.lpush(this.listkey, msg);
						break;
					case "pubsub":
					default:
						jedis.publish(this.channel, msg);
						break;
				}
				this.succeededMessageCount.inc();
				this.succeededMessageMeter.mark();
			} catch (Exception e) {
				if (e instanceof JedisConnectionException && cxErrors++ < 2) {
					logger.warn("lost connection to server, trying to reconnect...", e);
					jedis.disconnect();
					jedis.connect();
					continue;
				} else {
					this.failedMessageCount.inc();
					this.failedMessageMeter.mark();
					logger.error("Exception during put", e);

					if (!context.getConfig().ignoreProducerError) {
						throw new RuntimeException(e);
					}
				}
			}

			if (r.isTXCommit()) {
				context.setPosition(r.getNextPosition());
			}

			if (logger.isDebugEnabled()) {
				switch (redistype) {
					case "lpush":
						logger.debug("->  queue:" + listkey + ", msg:" + msg);
						break;
					case "pubsub":
					default:
						logger.debug("->  channel:" + channel + ", msg:" + msg);
						break;
				}
			}

			break;
		}
	}

	@Override
	public void requestStop() {
		jedis.close();
	}

	@Override
	public void awaitStop(Long timeout) { }

	@Override
	public StoppableTask getStoppableTask() {
		return this;
	}
}
