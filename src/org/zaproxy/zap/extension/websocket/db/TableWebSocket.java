/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.websocket.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.hsqldb.jdbc.jdbcClob;
import org.parosproxy.paros.db.AbstractTable;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.zaproxy.zap.extension.websocket.WebSocketChannelDAO;
import org.zaproxy.zap.extension.websocket.WebSocketMessage;
import org.zaproxy.zap.extension.websocket.WebSocketMessageDAO;
import org.zaproxy.zap.extension.websocket.fuzz.WebSocketFuzzMessageDAO;

/**
 * Manages writing and reading WebSocket messages to the database.
 */
public class TableWebSocket extends AbstractTable {
	private static Logger logger = Logger.getLogger(TableWebSocket.class);
	
	private Set<Integer> channelIds;
    
	private PreparedStatement psSelectMessagesByChannelId;
    private PreparedStatement psInsertMessage;
    
	private PreparedStatement psSelectChannelIds;
	private PreparedStatement psSelectChannels;
	
    private PreparedStatement psInsertChannel;
	private PreparedStatement psUpdateChannel;
	
	private PreparedStatement psUpdateHistoryFk;

	private PreparedStatement psDeleteChannel;
	private PreparedStatement psDeleteMessagesByChannelId;

	private PreparedStatement psInsertFuzz;

//	private PreparedStatement psMergeChannel;

    /**
     * Create tables if not already available
     */
    @Override
    protected void reconnect(Connection conn) throws SQLException {
    	ResultSet rs = conn.getMetaData().getTables(null, null, "WEBSOCKET_CHANNEL", null);
    	try {
	        if (!rs.next()) {
				// need to create the tables
				PreparedStatement stmt = conn
						.prepareStatement("CREATE CACHED TABLE websocket_channel ("
								+ "id BIGINT PRIMARY KEY,"
								+ "host VARCHAR(255) NOT NULL,"
								+ "port INTEGER NOT NULL,"
								+ "start_timestamp TIMESTAMP NOT NULL,"
								+ "end_timestamp TIMESTAMP NULL,"
								+ "history_id INTEGER NULL,"
								+ "FOREIGN KEY (history_id) REFERENCES HISTORY(HISTORYID) ON DELETE SET NULL ON UPDATE SET NULL"
								+ ")");
				stmt.execute();
				stmt.close();
				
				stmt = conn.prepareStatement("CREATE CACHED TABLE websocket_message ("
								+ "id BIGINT NOT NULL,"
								+ "websocket_channel_id BIGINT NOT NULL,"
								+ "timestamp TIMESTAMP NOT NULL,"
								+ "opcode TINYINT NOT NULL,"
								+ "payload LONGVARCHAR NOT NULL,"
								+ "payload_length BIGINT NOT NULL,"
								+ "is_outgoing BOOLEAN NOT NULL,"
								+ "PRIMARY KEY (id, websocket_channel_id),"
								+ "FOREIGN KEY (websocket_channel_id) REFERENCES websocket_channel(id)"
								+ ")");
				stmt.execute();
				stmt.close();
				
				stmt = conn.prepareStatement("CREATE CACHED TABLE websocket_message_fuzz ("
								+ "id BIGINT NOT NULL,"
								+ "websocket_message_id BIGINT NOT NULL,"
								+ "websocket_channel_id BIGINT NOT NULL,"
								+ "state VARCHAR(50) NOT NULL,"
								+ "fuzz LONGVARCHAR NOT NULL,"
								+ "PRIMARY KEY (id, websocket_message_id, websocket_channel_id),"
								+ "FOREIGN KEY (websocket_message_id, websocket_channel_id) REFERENCES websocket_message(id, websocket_channel_id) ON DELETE CASCADE"
								+ ")");
				stmt.execute();
				stmt.close();
				
				channelIds = new HashSet<Integer>();
			} else {
				channelIds = null;
			}
    	} finally {
    		rs.close();
    	}

//        psSelectMessages = conn.prepareStatement("SELECT m.* "
//        		+ "FROM websocket_message AS m "
//        		+ "ORDER BY m.timestamp, m.websocket_channel_id, m.id "
//        		+ "LIMIT ? "
//        		+ "OFFSET ?");
//		
//        psCountMessages = conn.prepareStatement("SELECT COUNT(id) "
//				+ "FROM websocket_message");
//
//        psSelectMessagesForChannel = conn.prepareStatement("SELECT m.* "
//        		+ "FROM websocket_message AS m "
//        		+ "WHERE m.websocket_channel_id = ?"
//        		+ "ORDER BY m.timestamp, m.websocket_channel_id, m.id "
//        		+ "LIMIT ? "
//        		+ "OFFSET ?");
        
        psSelectMessagesByChannelId = conn.prepareStatement("SELECT m.*, f.id AS fuzz_id, f.state, f.fuzz "
				+ "FROM websocket_message AS m "
        		+ "JOIN websocket_channel AS c ON m.websocket_channel_id = c.id "
				+ "LEFT OUTER JOIN websocket_message_fuzz f ON m.id = f.websocket_message_id AND m.websocket_channel_id = f.websocket_channel_id "
				+ "WHERE c.id = ? "
				+ "ORDER BY m.id");
        
        psSelectChannelIds = conn.prepareStatement("SELECT c.id "
        		+ "FROM websocket_channel AS c "
        		+ "ORDER BY c.id");
        
        psSelectChannels = conn.prepareStatement("SELECT c.* "
        		+ "FROM websocket_channel AS c "
        		+ "ORDER BY c.id");

        // id goes last to be consistent with update query
		psInsertChannel = conn.prepareStatement("INSERT INTO "
				+ "websocket_channel (host, port, start_timestamp, end_timestamp, history_id, id) "
				+ "VALUES (?,?,?,?,?,?)");
		
		psDeleteChannel = conn.prepareStatement("DELETE FROM websocket_channel "
				+ "WHERE id = ?");
		psDeleteMessagesByChannelId = conn.prepareStatement("DELETE FROM websocket_message "
				+ "WHERE websocket_channel_id = ?");

		psUpdateChannel = conn.prepareStatement("UPDATE websocket_channel SET "
				+ "host = ?, port = ?, start_timestamp = ?, end_timestamp = ?, history_id = ? "
				+ "WHERE id = ?");
		
		psUpdateHistoryFk = conn.prepareStatement("UPDATE websocket_channel SET "
				+ "history_id = ? "
				+ "WHERE id = ?");
		
//		psMergeChannel = conn.prepareStatement("MERGE INTO websocket_channel AS old "
//				+ "USING (VALUES(?,?,?,?,?,?)) "
//				+ "AS new (id, host, port, start_timestamp, end_timestamp, history_id) "
//				+ "ON old.id = new.id "
//				+ "WHEN MATCHED THEN UPDATE SET old.host = new.host, old.port = new.port, old.start_timestamp = new.start_timestamp, old.end_timestamp = new.end_timestamp, old.history_id = new.history_id " 
//			    + "WHEN NOT MATCHED THEN INSERT VALUES (new.id, new.host, new.port, new.start_timestamp, new.end_timestamp, new.history_id)");

		psInsertMessage = conn.prepareStatement("INSERT INTO "
				+ "websocket_message (id, websocket_channel_id, timestamp, opcode, payload, payload_length, is_outgoing) "
				+ "VALUES (?,?,?,?,?,?,?)");
		
		psInsertFuzz = conn.prepareStatement("INSERT INTO "
				+ "websocket_message_fuzz (id, websocket_message_id, websocket_channel_id, state, fuzz) "
				+ "VALUES (?,?,?,?,?)");
		
		if (channelIds == null) {
			channelIds = new HashSet<Integer>();
			psSelectChannelIds.execute();
			
			rs = psSelectChannelIds.getResultSet();
			while (rs.next()) {
				channelIds.add(rs.getInt(1));
			}
		}
    }

	/**
     * Get messages.
     * 
     * @param host
     * @return
     * @throws HttpMalformedHeaderException
     * @throws SQLException
     */
	public synchronized List<WebSocketMessageDAO> selectMessages(Integer channelId) throws HttpMalformedHeaderException, SQLException {
	    psSelectMessagesByChannelId.setInt(1, channelId);
	    psSelectMessagesByChannelId.execute();
		ResultSet rs = psSelectMessagesByChannelId.getResultSet();
		
		List<WebSocketMessageDAO> messages = null;
		messages = buildMessageDAOs(rs);

		return messages;
	}
	
	/**
	 * 
	 * @param rs
	 * @return
	 * @throws HttpMalformedHeaderException
	 * @throws SQLException
	 */
	private List<WebSocketMessageDAO> buildMessageDAOs(ResultSet rs) throws SQLException {
		List<WebSocketMessageDAO> messages = new ArrayList<WebSocketMessageDAO>();
		try {
			while (rs.next()) {
				WebSocketMessageDAO dao;
				
				if (rs.getInt("fuzz_id") != 0) {
					WebSocketFuzzMessageDAO fuzzDao = new WebSocketFuzzMessageDAO();
					fuzzDao.fuzzId = rs.getInt("fuzz_id");
					fuzzDao.state = WebSocketFuzzMessageDAO.State.valueOf(rs.getString("state"));
					fuzzDao.fuzz = rs.getString("fuzz");
					
					dao = fuzzDao;
				} else {
					dao = new WebSocketMessageDAO();
				}
				
				dao.channelId = rs.getInt("websocket_channel_id");
				dao.messageId = rs.getInt("id");
				dao.setTime(rs.getTimestamp("timestamp"));
				dao.opcode = rs.getInt("opcode");
				dao.readableOpcode = WebSocketMessage.opcode2string(dao.opcode);
				dao.payload = rs.getString("payload");				
				dao.isOutgoing = rs.getBoolean("is_outgoing");
				dao.payloadLength = rs.getInt("payload_length");
				
				messages.add(dao);
			}
		} finally {
			rs.close();
		}
		
		return messages;
	}

	/**
	 * Prepares a {@link PreparedStatement} instance on the fly.
	 * 
	 * @param criteria
	 * @param opcodes Null when all opcodes should be retrieved.
	 * @return
	 * @throws SQLException
	 */
	public synchronized int getMessageCount(WebSocketMessageDAO criteria, List<Integer> opcodes) throws SQLException {
		String query = "SELECT COUNT(m.id) FROM websocket_message AS m "
				+ "LEFT OUTER JOIN websocket_message_fuzz f "
        		+ "ON m.id = f.websocket_message_id AND m.websocket_channel_id = f.websocket_channel_id "
				+ "<where> ";
		PreparedStatement stmt = buildMessageCriteriaStatement(query, criteria, opcodes);
		return executeAndGetRowCount(stmt);
	}

	private int executeAndGetRowCount(PreparedStatement stmt) throws SQLException {
		stmt.execute();
		ResultSet rs = stmt.getResultSet();
		try {
			if (rs.next()) {
				return rs.getInt(1);
			}
			return 0;
		} finally {
			rs.close();
		}
	}

	public synchronized int getIndexOf(WebSocketMessageDAO criteria, List<Integer> opcodes) throws SQLException {
		String query = "SELECT COUNT(m.id) "
        		+ "FROM websocket_message AS m "
				+ "LEFT OUTER JOIN websocket_message_fuzz f "
        		+ "ON m.id = f.websocket_message_id AND m.websocket_channel_id = f.websocket_channel_id "
        		+ "<where> AND m.id < ?";
		PreparedStatement stmt = buildMessageCriteriaStatement(query, criteria, opcodes);
		
		int paramsCount = stmt.getParameterMetaData().getParameterCount();
		stmt.setInt(paramsCount, criteria.messageId);
		
		return executeAndGetRowCount(stmt);
	}

	/**
	 * Tags are not loaded.
	 * 
	 * @param criteria
	 * @param opcodes
	 * @param offset
	 * @param limit
	 * @return
	 * @throws SQLException
	 */
	public synchronized List<WebSocketMessageDAO> getMessages(WebSocketMessageDAO criteria, List<Integer> opcodes, int offset, int limit) throws SQLException {
		String query = "SELECT m.*, f.id AS fuzz_id, f.state, f.fuzz "
        		+ "FROM websocket_message AS m "
				+ "LEFT OUTER JOIN websocket_message_fuzz f "
        		+ "ON m.id = f.websocket_message_id AND m.websocket_channel_id = f.websocket_channel_id "
        		+ "<where> "
        		+ "ORDER BY m.websocket_channel_id, m.id "
        		+ "LIMIT ? "
        		+ "OFFSET ?";

		PreparedStatement stmt;
		try {
			stmt = buildMessageCriteriaStatement(query, criteria, opcodes);
		} catch (SQLException e) {
			if (getConnection().isClosed()) {
				return new ArrayList<WebSocketMessageDAO>();
			} else {
				throw e;
			}
		}
		
		int paramsCount = stmt.getParameterMetaData().getParameterCount();
		stmt.setInt(paramsCount - 1, limit);
		stmt.setInt(paramsCount, offset);
		
		stmt.execute();
		
		return buildMessageDAOs(stmt.getResultSet());
	}

	private PreparedStatement buildMessageCriteriaStatement(String query, WebSocketMessageDAO criteria, List<Integer> opcodes) throws SQLException {
		List<String> where = new ArrayList<String>();
		List<Object> params = new ArrayList<Object>();

		if (criteria.channelId != null) {
			where.add("m.websocket_channel_id = ?");
			params.add(criteria.channelId);
		}
		
		if (criteria.isOutgoing != null) {
			where.add("m.is_outgoing = ?");
			params.add(criteria.isOutgoing);
		}
		
		if (opcodes != null && !opcodes.isEmpty()) {
			StringBuilder opcodeExpr = new StringBuilder("(");
			int opcodesCount = opcodes.size();
			
			for (int i = 0; i < opcodesCount; i++) {
				params.add(opcodes.get(i));
				
				opcodeExpr.append("m.opcode = ?");
				if ((i + 1) < opcodesCount) {
					opcodeExpr.append(" OR ");
				}
			}
			
			opcodeExpr.append(")");
			where.add(opcodeExpr.toString());
		}
		
		if (criteria instanceof WebSocketFuzzMessageDAO) {
			WebSocketFuzzMessageDAO fuzzCriteria = (WebSocketFuzzMessageDAO) criteria;
			if (fuzzCriteria.fuzzId != null) {
				params.add(fuzzCriteria.fuzzId);
				where.add("f.id = ?");
			}
		}
		
		return buildCriteriaStatementHelper(query, where, params);
	}

	public WebSocketMessagePrimaryKey getMessagePrimaryKey(WebSocketMessageDAO dao) {
		return new WebSocketMessagePrimaryKey(dao.channelId, dao.messageId);
	}

	public List<WebSocketChannelDAO> getChannelItems() throws SQLException {
		psSelectChannels.execute();
		ResultSet rs = psSelectChannels.getResultSet();
		
		return buildChannelDAOs(rs);
	}

	private List<WebSocketChannelDAO> buildChannelDAOs(ResultSet rs) throws SQLException {
		List<WebSocketChannelDAO> channels = new ArrayList<WebSocketChannelDAO>();
		try {
			while (rs.next()) {
				WebSocketChannelDAO dao = new WebSocketChannelDAO();
				dao.channelId = rs.getInt("id");
				dao.host = rs.getString("host");
				dao.port = rs.getInt("port");
				dao.startTimestamp = rs.getTimestamp("start_timestamp").getTime();
				
				Time endTs = rs.getTime("end_timestamp");
				dao.endTimestamp = (endTs != null) ? endTs.getTime() : null;
				
				dao.historyId = rs.getInt("history_id");
				
				channels.add(dao);
			}
		} finally {
			rs.close();
		}
		
		return channels;
	}
	
	public void insertOrUpdateChannel(WebSocketChannelDAO dao) throws SQLException {
		synchronized (this) {
			PreparedStatement stmt;
			boolean addIdOnSuccess = false;
			
			// first, find out if already inserted
			if (channelIds.contains(dao.channelId)) {
				// proceed with update
				stmt = psUpdateChannel;
			} else {
				// proceed with insert
				stmt = psInsertChannel;
				addIdOnSuccess = true;
				logger.info("insert channel: " + dao.toString());
			}
	
			stmt.setString(1, dao.host);
			stmt.setInt(2, dao.port);
			stmt.setTimestamp(3, (dao.startTimestamp != null) ? new Timestamp(dao.startTimestamp) : null);
			stmt.setTimestamp(4, (dao.endTimestamp != null) ? new Timestamp(dao.endTimestamp) : null);
			stmt.setNull(5, Types.INTEGER);
			stmt.setInt(6, dao.channelId);
			
			try {
				stmt.execute();
				if (addIdOnSuccess) {
					channelIds.add(dao.channelId);
				}
			} catch (SQLException e) {
				throw e;
			}
			
			if (dao.historyId != null) {
				psUpdateHistoryFk.setInt(1, dao.historyId);
				psUpdateHistoryFk.setInt(2, dao.channelId);
				try {
					psUpdateHistoryFk.execute();
				} catch (SQLException e) {
					// safely ignore this exception
					// on shutdown, the history table is cleaned before
					// WebSocket channels are closed and updated
				}
			}
			
			// with newer version of HSQLDB I could have used the MERGE command
	//		psMergeChannel.setInt(1, dao.channelId);
	//		psMergeChannel.setString(2, dao.host);
	//		psMergeChannel.setInt(3, dao.port);
	//		psMergeChannel.setTimestamp(4, new Timestamp(dao.startTimestamp));
	//		psMergeChannel.setTimestamp(5, new Timestamp(dao.endTimestamp));
	//		psMergeChannel.setInt(6, dao.historyId);
	//		psMergeChannel.execute();	
		}
	}

	public void insertMessage(WebSocketMessageDAO dao) throws SQLException {

		// synchronize on whole object to avoid race conditions with insertOrUpdateChannel()
		synchronized (this) {
			if (!channelIds.contains(dao.channelId)) {
				throw new SQLException("channel not inserted: " + dao.channelId);
			}
			
			logger.info("insert message: " + dao.toString());
			try {
				psInsertMessage.setInt(1, dao.messageId);
				psInsertMessage.setInt(2, dao.channelId);
				psInsertMessage.setTimestamp(3, new Timestamp(dao.timestamp));
				psInsertMessage.setInt(4, dao.opcode);
				
				// TODO: save also binary messages, but how? as blob?
				psInsertMessage.setClob(5, new jdbcClob(dao.payload));
				psInsertMessage.setInt(6, dao.payloadLength);
				psInsertMessage.setBoolean(7, dao.isOutgoing);
				psInsertMessage.execute();
				
				if (dao instanceof WebSocketFuzzMessageDAO) {
					WebSocketFuzzMessageDAO fuzzDao = (WebSocketFuzzMessageDAO) dao;
					psInsertFuzz.setInt(1, fuzzDao.fuzzId);
					psInsertFuzz.setInt(2, fuzzDao.messageId);
					psInsertFuzz.setInt(3, fuzzDao.channelId);
					psInsertFuzz.setString(4, fuzzDao.state.toString());
					psInsertFuzz.setString(5, fuzzDao.fuzz);
					psInsertFuzz.execute();
				}
			} catch (SQLException e) {
				throw e;
			}
		}
	}

	public List<WebSocketChannelDAO> getChannels(WebSocketChannelDAO criteria) throws SQLException {
		String query = "SELECT c.* "
        		+ "FROM websocket_channel AS c "
        		+ "<where> "
        		+ "ORDER BY c.start_timestamp, c.id";

		PreparedStatement stmt;
		try {
			stmt = buildMessageCriteriaStatement(query, criteria);
		} catch (SQLException e) {
			if (getConnection().isClosed()) {
				return new ArrayList<WebSocketChannelDAO>();
			} else {
				throw e;
			}
		}
		
		stmt.execute();
		
		return buildChannelDAOs(stmt.getResultSet());
	}
	
	private PreparedStatement buildMessageCriteriaStatement(String query, WebSocketChannelDAO criteria) throws SQLException {
		List<String> where = new ArrayList<String>();
		List<Object> params = new ArrayList<Object>();
	
		if (criteria.channelId != null) {
			where.add("c.id = ?");
			params.add(criteria.channelId);
		}
		
		return buildCriteriaStatementHelper(query, where, params);
	}

	private PreparedStatement buildCriteriaStatementHelper(String query, List<String> where, List<Object> params) throws SQLException {
		int conditionsCount = where.size();
		if (conditionsCount > 0) {
			StringBuilder whereExpr = new StringBuilder();
		    int i = 0;
			for (String condition : where) {
				whereExpr.append(condition);
				
				i++;
				if (i < conditionsCount) {
					// one more will be appended
					whereExpr.append(" AND ");
				}
			}
			query = query.replace("<where>", "WHERE " + whereExpr.toString());
		} else {
			query = query.replace("<where> AND", "WHERE ");
			query = query.replace("<where> ", "");
		}
		
		PreparedStatement stmt = getConnection().prepareStatement(query.toString());
		int i = 1;
		for (Object param : params) {
			stmt.setObject(i++, param);
		}
		
		return stmt;
	}

	/**
	 * Deletes all entries from given channelId from database.
	 * 
	 * @param channelId
	 * @throws SQLException 
	 */
	public void purgeChannel(Integer channelId) throws SQLException {
		synchronized (this) {
			if (channelIds.contains(channelId)) {
				psDeleteMessagesByChannelId.setInt(1, channelId);
				psDeleteMessagesByChannelId.execute();
				
				psDeleteChannel.setInt(1, channelId);
				psDeleteChannel.execute();
				
				channelIds.remove(channelId);
			}
		}
	}
}