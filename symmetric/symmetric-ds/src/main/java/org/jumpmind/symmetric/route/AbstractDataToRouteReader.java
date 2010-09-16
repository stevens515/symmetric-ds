package org.jumpmind.symmetric.route;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.commons.lang.StringUtils;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.logging.ILog;
import org.jumpmind.symmetric.common.logging.LogFactory;
import org.jumpmind.symmetric.model.Channel;
import org.jumpmind.symmetric.model.Data;
import org.jumpmind.symmetric.service.IDataService;
import org.jumpmind.symmetric.service.ISqlProvider;
import org.jumpmind.symmetric.util.AppUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * This class is responsible for reading data for the purpose of routing. It
 * reads ahead and tries to keep a blocking queue populated for other threads to
 * process.
 */
abstract public class AbstractDataToRouteReader implements IDataToRouteReader {

    final protected ILog log = LogFactory.getLog(getClass());

    protected int fetchSize;

    protected BlockingQueue<Data> dataQueue;

    protected ISqlProvider sqlProvider;

    protected DataSource dataSource;

    protected RouterContext context;

    protected IDataService dataService;

    protected boolean reading = true;

    protected int maxQueueSize;

    protected static final int DEFAULT_QUERY_TIMEOUT = 300;

    protected int queryTimeout = DEFAULT_QUERY_TIMEOUT;

    public AbstractDataToRouteReader(DataSource dataSource, int queryTimeout, int maxQueueSize,
            ISqlProvider sqlProvider, int fetchSize, RouterContext context, IDataService dataService) {
        this.maxQueueSize = maxQueueSize;
        this.dataSource = dataSource;
        this.dataQueue = new LinkedBlockingQueue<Data>(maxQueueSize);
        this.sqlProvider = sqlProvider;
        this.context = context;
        this.fetchSize = fetchSize;
        this.queryTimeout = queryTimeout;
        this.dataService = dataService;
    }

    public Data take() {
        Data data = null;
        try {
            data = dataQueue.poll(queryTimeout == 0 ? 600 : queryTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn(e);
        }

        if (data instanceof EOD) {
            return null;
        } else {
            return data;
        }
    }

    /**
     * Start out by selecting only the rows that need routed.
     */
    abstract protected PreparedStatement prepareStatment(Connection c) throws SQLException;

    /**
     * If the first SQL statement becomes too unwieldy, offer the opportunity to use a less efficient
     * but more likely to execute query.
     */
    abstract protected PreparedStatement prepareSecondaryStatement(Connection c) throws SQLException;

    protected String getSql(String sqlName, Channel channel) {
        String select = sqlProvider.getSql(sqlName);
        if (!channel.isUseOldDataToRoute()) {
            select = select.replace("d.old_data", "''");
        }
        if (!channel.isUseRowDataToRoute()) {
            select = select.replace("d.row_data", "''");
        }
        if (!channel.isUsePkDataToRoute()) {
            select = select.replace("d.pk_data", "''");
        }
        return select;
    }

    public void run() {
        try {
            execute(true);
        } catch (Throwable ex) {
            log.error(ex);
            if (ex instanceof BadSqlGrammarException) {
                try {
                    execute(false);
                } catch (Throwable ex2) {
                    log.error(ex2);
                }
            }
        }
    }

    protected void execute(final boolean firstTry) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
        jdbcTemplate.execute(new ConnectionCallback<Integer>() {
            public Integer doInConnection(Connection c) throws SQLException, DataAccessException {
                int dataCount = 0;
                PreparedStatement ps = null;
                ResultSet rs = null;
                boolean autoCommit = c.getAutoCommit();
                try {
                    c.setAutoCommit(false);
                    String channelId = context.getChannel().getChannelId();
                    if (firstTry) {
                        ps = prepareStatment(c);
                    } else {
                        ps = prepareSecondaryStatement(c);
                    }
                    long ts = System.currentTimeMillis();
                    rs = ps.executeQuery();
                    long executeTimeInMs = System.currentTimeMillis()-ts;
                    context.incrementStat(executeTimeInMs, RouterContext.STAT_QUERY_TIME_MS);
                    if (executeTimeInMs > Constants.LONG_OPERATION_THRESHOLD) {
                        log.warn("RoutedDataSelectedInTime", executeTimeInMs, channelId);
                    }

                    int toRead = maxQueueSize - dataQueue.size();
                    List<Data> memQueue = new ArrayList<Data>(toRead);
                    ts = System.currentTimeMillis();
                    while (rs.next() && reading) {

                        if (StringUtils.isBlank(rs.getString(13))) {
                            Data data = dataService.readData(rs);
                            context.setLastDataIdForTransactionId(data);
                            memQueue.add(data);
                            dataCount++;
                            context.incrementStat(System.currentTimeMillis() - ts,
                                    RouterContext.STAT_READ_DATA_MS);
                        } else {
                            context.incrementStat(System.currentTimeMillis() - ts,
                                    RouterContext.STAT_REREAD_DATA_MS);
                        }

                        ts = System.currentTimeMillis();

                        if (toRead == 0) {
                            copyToQueue(memQueue);
                            toRead = maxQueueSize - dataQueue.size();
                            memQueue = new ArrayList<Data>(toRead);
                        } else {
                            toRead--;
                        }

                        context.incrementStat(System.currentTimeMillis() - ts,
                                RouterContext.STAT_ENQUEUE_DATA_MS);

                        ts = System.currentTimeMillis();
                    }

                    ts = System.currentTimeMillis();

                    copyToQueue(memQueue);

                    context.incrementStat(System.currentTimeMillis() - ts,
                            RouterContext.STAT_ENQUEUE_DATA_MS);

                    return dataCount;

                } finally {
                    JdbcUtils.closeResultSet(rs);
                    JdbcUtils.closeStatement(ps);
                    rs = null;
                    ps = null;

                    c.commit();
                    c.setAutoCommit(autoCommit);

                    boolean done = false;
                    do {
                        done = dataQueue.offer(new EOD());
                        if (!done) {
                            AppUtils.sleep(50);
                        }
                    } while (!done && reading);
                    
                    reading = false;

                }
            }
        });
    }

    protected void copyToQueue(List<Data> memQueue) {
        while (memQueue.size() > 0 && reading) {
            Data d = memQueue.get(0);
            if (dataQueue.offer(d)) {
                memQueue.remove(0);
            } else {
                AppUtils.sleep(50);
            }
        }
    }

    public boolean isReading() {
        return reading;
    }

    public void setReading(boolean reading) {
        this.reading = reading;
    }

    public BlockingQueue<Data> getDataQueue() {
        return dataQueue;
    }

    class EOD extends Data {

    }
}
