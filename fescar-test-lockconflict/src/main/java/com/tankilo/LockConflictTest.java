package com.tankilo;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fescar.common.exception.ShouldNeverHappenException;
import com.alibaba.fescar.common.thread.NamedThreadFactory;
import com.alibaba.fescar.core.exception.TransactionException;
import com.alibaba.fescar.rm.RMClientAT;
import com.alibaba.fescar.rm.datasource.DataSourceProxy;
import com.alibaba.fescar.tm.TMClient;
import com.alibaba.fescar.tm.api.DefaultFailureHandlerImpl;
import com.alibaba.fescar.tm.api.FailureHandler;
import com.alibaba.fescar.tm.api.GlobalTransaction;
import com.alibaba.fescar.tm.api.GlobalTransactionContext;
import com.alibaba.fescar.tm.api.TransactionalExecutor;
import com.alibaba.fescar.tm.api.TransactionalTemplate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LockConflictTest {

    private static DataSourceProxy dataSourceProxy;

    public static void main(String[] args) throws Throwable {
        DruidDataSource druidDataSource = new DruidDataSource();
        druidDataSource.setUrl("jdbc:mysql://localhost:3306/fescar");
        druidDataSource.setUsername("fescar");
        druidDataSource.setPassword("fescar");
        druidDataSource.setInitialSize(5);
        druidDataSource.init();

        dataSourceProxy = new DataSourceProxy(druidDataSource);

        LockConflictTest app = new LockConflictTest();
        app.demoByHighLevelAPI();

        System.in.read();
    }

    private void init() {
        String applicationId = "my_app";
        String transactionServiceGroup = "my_test_tx_group";
        TMClient.init(applicationId, transactionServiceGroup);
        RMClientAT.init(applicationId, transactionServiceGroup);
    }

    /**
     *
     * @throws SQLException
     */
    private static void businessCall1() throws SQLException {
        Connection con = null;
        try {
            con = dataSourceProxy.getConnection();
            try (PreparedStatement pt = con.prepareStatement("update user_money_a set money = money - ? where 1=1")) {
                pt.setInt(1, 1);
                pt.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != con) {
                con.close();
            }
        }
    }

    /**
     * Demo code for High Level API (TransactionalTemplate) usage.
     *
     * @throws Throwable business exception
     */
    public void demoByHighLevelAPI() throws Throwable {
        // 0. init
        init();

        // 0.1 prepare for the template instance
        TransactionalTemplate transactionalTemplate = new TransactionalTemplate();

        // 0.2 prepare for the failure handler (this is optional)
        FailureHandler failureHandler = new DefaultFailureHandlerImpl();

        try {
            // run you business in template
            transactionalTemplate.execute(new TransactionalExecutor() {
                @Override
                public Object execute() throws Throwable {
                    // Do Your BusinessService
                    businessCall1();
                    return null;
                }

                @Override
                public int timeout() {
                    return 300000;
                }

                @Override
                public String name() {
                    return "my_tx_instance";
                }
            });
        } catch (TransactionalExecutor.ExecutionException e) {
            TransactionalExecutor.Code code = e.getCode();
            switch (code) {
                case RollbackDone:
                    throw e.getOriginalException();
                case BeginFailure:
                    failureHandler.onBeginFailure(e.getTransaction(), e.getCause());
                    throw e.getCause();
                case CommitFailure:
                    failureHandler.onCommitFailure(e.getTransaction(), e.getCause());
                    throw e.getCause();
                case RollbackFailure:
                    failureHandler.onRollbackFailure(e.getTransaction(), e.getCause());
                    throw e.getCause();
                default:
                    throw new ShouldNeverHappenException("Unknown TransactionalExecutor.Code: " + code);

            }
        }

    }
}
