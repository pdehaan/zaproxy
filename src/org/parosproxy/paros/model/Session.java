/*
 *
 * Paros and its related class files.
 * 
 * Paros is an HTTP/HTTPS proxy for assessing web application security.
 * Copyright (C) 2003-2004 Chinotec Technologies Company
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Clarified Artistic License
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Clarified Artistic License for more details.
 * 
 * You should have received a copy of the Clarified Artistic License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
// ZAP: 2011/05/15 Support for exclusions
// ZAP: 2012/02/11 Re-ordered icons, added spider icon and notify via SiteMap
// ZAP: 2012/02/18 Rationalised session handling
// ZAP: 2012/04/23 Added @Override annotation to all appropriate methods and
// removed unnecessary casts.
// ZAP: 2012/05/15 Changed the method parse() to get the session description.
// ZAP: 2012/06/11 Changed the JavaDoc of the method isNewState().
// ZAP: 2012/07/23 Added excludeFromWebSocketRegexs list, getter and setter.
// Load also on open() from DB.
// ZAP: 2012/07/29 Issue 43: Added support for Scope
// ZAP: 2012/08/01 Issue 332: added support for Modes
// ZAP: 2012/08/07 Added method for getting all Nodes in Scope

package org.parosproxy.paros.model;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.common.FileXML;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.db.RecordSessionUrl;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.View;
import org.xml.sax.SAXException;
import org.zaproxy.zap.extension.ascan.ExtensionActiveScan;
import org.zaproxy.zap.extension.spider.ExtensionSpider;
import org.zaproxy.zap.extension.websocket.ExtensionWebSocket;


public class Session extends FileXML {
	
    // ZAP: Added logger
    private static Logger log = Logger.getLogger(Session.class);

	private static final String ROOT = "session";
	
	private static final String SESSION_DESC = "sessionDesc";
	private static final String SESSION_ID = "sessionId";
	private static final String SESSION_NAME = "sessionName";
	
	private static final String[] PATH_SESSION_DESC = {ROOT, SESSION_DESC};	
	private static final String[] PATH_SESSION_ID = {ROOT, SESSION_ID};
	private static final String[] PATH_SESSION_NAME = {ROOT, SESSION_NAME};


	// other runtime members
	private Model model = null;
	private String fileName = "";
	private String sessionDesc = "";
	private List<String> includeInScopeRegexs = new ArrayList<String>();
	private List<String> excludeFromScopeRegexs = new ArrayList<String>();
	private List<String> excludeFromProxyRegexs = new ArrayList<String>();
	private List<String> excludeFromScanRegexs = new ArrayList<String>();
	private List<String> excludeFromSpiderRegexs = new ArrayList<String>();

	private List<String> excludeFromWebSocketRegexs = new ArrayList<String>();
    
    private List<Pattern> includeInScopePatterns = new ArrayList<Pattern>();
    private List<Pattern> excludeFromScopePatterns = new ArrayList<Pattern>();

	// parameters in XML
	private long sessionId = 0;
	private String sessionName = "";
	private SiteMap siteTree = null;
	
	/**
	 * Constructor for the current session.  The current system time will be used as the session ID.
	 * @param model
	 */
	protected Session(Model model) {
		super(ROOT);

		/*try {
			parseFile("xml/untitledsession.xml");
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}*/
		
		// add session variable here
		setSessionId(System.currentTimeMillis());
		setSessionName(Constant.messages.getString("session.untitled"));
		setSessionDesc("");
		
		// create default object
		this.siteTree = SiteMap.createTree(model);
		
		this.model = model;
		
	}
	
	protected void discard() {
	    try {
	        model.getDb().getTableHistory().deleteHistorySession(getSessionId());
        } catch (SQLException e) {
        	// ZAP: Log exceptions
        	log.warn(e.getMessage(), e);
        }
	}
	
	
    /**
     * @return Returns the sessionDesc.
     */
    public String getSessionDesc() {
        return sessionDesc;
    }
	
	/**
	 * @return Returns the sessionId.
	 */
	public long getSessionId() {
		return sessionId;
	}
	/**
	 * @return Returns the name.
	 */
	public String getSessionName() {
		return sessionName;
	}
    /**
     * @return Returns the siteTree.
     */
    public SiteMap getSiteTree() {
        return siteTree;
    }
	
	

    /**
     * Tells whether this session is in a new state or not. A session is in a
     * new state if it was never saved or it was not loaded from an existing
     * session.
     * 
     * @return {@code true} if this session is in a new state, {@code false}
     *         otherwise.
     */
    // ZAP: Changed the JavaDoc.
    public boolean isNewState() {
        return fileName.equals("");
    }

    
    protected void open(final File file, final SessionListener callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Exception thrownException = null;
                try {
                    open(file.getAbsolutePath());
                } catch (Exception e) {
                    thrownException = e;
                }
                if (callback != null) {
                    callback.sessionOpened(file, thrownException);
                }
            }
        });
        t.setPriority(Thread.NORM_PRIORITY-2);
        t.start();
    }

	protected void open(String fileName) throws SQLException, SAXException, IOException, Exception {

		readAndParseFile(fileName);
		model.getDb().close(false);
		model.getDb().open(fileName);
		this.fileName = fileName;
		
		//historyList.removeAllElements();

		SiteNode newRoot = new SiteNode(siteTree, -1, Constant.messages.getString("tab.sites"));
		siteTree.setRoot(newRoot);

		// update history reference
		List<Integer> list = model.getDb().getTableHistory().getHistoryList(getSessionId(), HistoryReference.TYPE_MANUAL);
		HistoryReference historyRef = null;

	    // Load the session urls
	    this.setIncludeInScopeRegexs(
	    		sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_INCLUDE_IN_SCOPE)));

	    this.setExcludeFromScopeRegexs(
	    		sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCOPE)));

	    this.setExcludeFromProxyRegexs(
	    		sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_EXCLUDE_FROM_PROXY)));

	    this.setExcludeFromScanRegexs(
	    		sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCAN)));

	    this.setExcludeFromSpiderRegexs(
	    		sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_EXCLUDE_FROM_SPIDER)));
	    		
	    this.setExcludeFromWebSocketRegexs(
				sessionUrlListToStingList(model.getDb().getTableSessionUrl().getUrlsForType(RecordSessionUrl.TYPE_EXCLUDE_FROM_WEBSOCKET)));
	    
		for (int i=0; i<list.size(); i++) {
			// ZAP: Removed unnecessary cast.
			int historyId = list.get(i).intValue();

			try {
				historyRef = new HistoryReference(historyId);
				SiteNode sn = getSiteTree().addPath(historyRef);
				// ZAP: Load alerts from db
				historyRef.loadAlerts();
				if (sn != null) {
					sn.setIncludedInScope(this.isIncludedInScope(sn), false);
					sn.setExcludedFromScope(this.isExcludedFromScope(sn), false);
				}

				if (i % 100 == 99) Thread.yield();
			} catch (Exception e) {
            	// ZAP: Log exceptions
            	log.warn(e.getMessage(), e);
			};
			
		}
		
		// update siteTree reference
		list = model.getDb().getTableHistory().getHistoryList(getSessionId(), HistoryReference.TYPE_SPIDER);
		
		for (int i=0; i<list.size(); i++) {
			// ZAP: Removed unnecessary cast.
			int historyId = list.get(i).intValue();

			try {
				historyRef = new HistoryReference(historyId);
				getSiteTree().addPath(historyRef);

				if (i % 100 == 99) Thread.yield();

			} catch (Exception e) {
            	// ZAP: Log exceptions
            	log.warn(e.getMessage(), e);
			};
			
			
		}
		
        // XXX Temporary "hack" to check if ZAP is in GUI mode. Calling
        // View.getSingleton() creates the View, if a View exists and the API
        // was not enabled (through configuration) the API becomes disabled
        // everywhere (including daemon mode).
        // Note: the API needs to be enabled all the time in daemon mode.
		if (View.isInitialised()) {
		    // ZAP: expand root
		    View.getSingleton().getSiteTreePanel().expandRoot();
		}

		System.gc();
	}
	
	private List<String> sessionUrlListToStingList(List<RecordSessionUrl> rsuList) {
	    List<String> urlList = new ArrayList<String>(rsuList.size());
	    for (RecordSessionUrl url : rsuList) {
	    	urlList.add(url.getUrl());
	    }
	    return urlList;
	}
	
	@Override
	protected void parse() throws Exception {
	    
	    long tempSessionId = 0;
	    String tempSessionName = "";
	    String tempSessionDesc = "";
	    
	    // use temp variable to check.  Exception will be flagged if any error.
		tempSessionId = Long.parseLong(getValue(SESSION_ID));
		tempSessionName = getValue(SESSION_NAME);
		// ZAP: Changed to get the session description and use the variable
		// tempSessionDesc.
		tempSessionDesc = getValue(SESSION_DESC);

		// set member variable after here
		sessionId = tempSessionId;
		sessionName = tempSessionName;
		sessionDesc = tempSessionDesc;
		


	}

	/**
	 * Asynchronous call to save a session.
	 * @param fileName
	 * @param callback
	 */
    protected void save(final String fileName, final SessionListener callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Exception thrownException = null;
                try {
                    save(fileName);
                } catch (Exception e) {
                	// ZAP: Log exceptions
                	log.warn(e.getMessage(), e);
                    thrownException = e;
                }
                if (callback != null) {
                    callback.sessionSaved(thrownException);
                }
            }
        });
        t.setPriority(Thread.NORM_PRIORITY-2);
        t.start();
    }
    
    /**
     * Synchronous call to save a session.
     * @param fileName
     * @throws Exception
     */
	protected void save(String fileName) throws Exception {
	    saveFile(fileName);
		if (isNewState()) {
		    model.moveSessionDb(fileName);
		} else {
		    if (!this.fileName.equals(fileName)) {
		        // copy file to new fileName
		        model.copySessionDb(this.fileName, fileName);
		    }
		}
	    this.fileName = fileName;
		
		synchronized (siteTree) {
		    saveSiteTree((SiteNode) siteTree.getRoot());
		}
		
		model.getDb().getTableSession().update(getSessionId(), getSessionName());
	}
	
    /**
     * @param sessionDesc The sessionDesc to set.
     */
    public void setSessionDesc(String sessionDesc) {
        this.sessionDesc = sessionDesc;
		setValue(PATH_SESSION_DESC, sessionDesc);
    }
	
	/**
	 * @param sessionId The sessionId to set.
	 */
	public void setSessionId(long sessionId) {
		this.sessionId = sessionId;
		//setText(SESSION_ID, Long.toString(sessionId));
		setValue(PATH_SESSION_ID, Long.toString(sessionId));

	}
	/**
	 * @param name The name to set.
	 */
	public void setSessionName(String name) {
		this.sessionName = name;
		//setText(SESSION_NAME, name);
		setValue(PATH_SESSION_NAME, name);
		
	}

    
    public String getFileName() {
        return fileName;
    }
    
    private void saveSiteTree(SiteNode node) {
        HttpMessage msg = null;

        if (!node.isRoot()) {
            if (node.getHistoryReference().getHistoryType() < 0) {
                // -ve means to be saved
                saveNodeMsg(msg);
            }
        }
        
        for (int i=0; i<node.getChildCount(); i++) {
            try {
                saveSiteTree((SiteNode) node.getChildAt(i));
            } catch (Exception e) {
            	// ZAP: Log exceptions
            	log.warn(e.getMessage(), e);
            }
        }
        
    }
    
    private void saveNodeMsg(HttpMessage msg) {
        // nothing need to be done
    }
    
    public String getSessionFolder() {
        String result = "";
        if (fileName.equals("")) {
//            result = Constant.FOLDER_SESSION;
            result = Constant.getInstance().FOLDER_SESSION;
        } else {
            File file = new File(fileName);
            result = file.getParent();
        }
        return result;
    }

	public List<String> getExcludeFromProxyRegexs() {
		return excludeFromProxyRegexs;
	}
	
	
	private List<String> stripEmptyLines(List<String> list) {
		List<String> slist = new ArrayList<String>();
		for (String str : list) {
			if (str.length() > 0) {
				slist.add(str);
			}
		}
		return slist;
	}
	
	private void refreshScope(SiteNode node) {
		if (node == null) {
			return;
		}
		if (node.isIncludedInScope() == ! this.isIncludedInScope(node)) {
			// Its 'scope' state has changed, so switch it!
			node.setIncludedInScope(!node.isIncludedInScope(), false);
		}
		if (node.isExcludedFromScope() == ! this.isExcludedFromScope(node)) {
			// Its 'scope' state has changed, so switch it!
			node.setExcludedFromScope(!node.isExcludedFromScope(), false);
		}
		// Recurse down
		if (node.getChildCount() > 0) {
	    	SiteNode c = (SiteNode) node.getFirstChild();
	    	while (c != null) {
	    		refreshScope(c);
	    		c = (SiteNode) node.getChildAfter(c);
	    	}
		}
	}

	private void refreshScope() {
        if (EventQueue.isDispatchThread()) {
        	refreshScope((SiteNode) siteTree.getRoot());
        	Control.getSingleton().sessionScopeChanged();
        } else {
            try {
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                    	refreshScope((SiteNode) siteTree.getRoot());
                    	Control.getSingleton().sessionScopeChanged();
                    }
                });
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
	}

	protected boolean isIncludedInScope(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isIncludedInScope(sn.getHierarchicNodeName());
	}
	
	private boolean isIncludedInScope(String url) {
		if (url == null) {
			return false;
		}
		if (url.indexOf("?") > 0) {
			// Strip off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		for (Pattern p : this.includeInScopePatterns) {
			if (p.matcher(url).matches()) {
				return true;
			}
		}
		return false;
	}

	protected boolean isExcludedFromScope(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isExcludedFromScope(sn.getHierarchicNodeName());
	}
	
	private boolean isExcludedFromScope(String url) {
		if (url == null) {
			return false;
		}
		if (url.indexOf("?") > 0) {
			// Strip off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		for (Pattern p : this.excludeFromScopePatterns) {
			if (p.matcher(url).matches()) {
				return true;
			}
		}
		return false;
	}

	public boolean isInScope(HistoryReference href) {
		if (href == null) {
			return false;
		}
		if (href.getSiteNode() != null) {
			return this.isInScope(href.getSiteNode());
		}
		try {
			HttpMessage msg = href.getHttpMessage();
			if (msg != null) {
				return this.isInScope(msg.getRequestHeader().getURI().toString());
			}
        } catch (Exception e) {
            log.error(e.getMessage(), e);
		}
		return false;
	}
	
	public boolean isInScope(SiteNode sn) {
		if (sn == null) {
			return false;
		}
		return isInScope(sn.getHierarchicNodeName());
	}
	
	public boolean isInScope(String url) {
		if (url.indexOf("?") > 0) {
			// String off any parameters
			url = url.substring(0, url.indexOf("?"));
		}
		if (! this.isIncludedInScope(url)) {
			// Not explicitly included
			return false;
		}
		// Check to see if its explicitly excluded
		return ! this.isExcludedFromScope(url);
	}

	/**
	 * Gets the nodes from the site tree which are "In Scope". Searches recursively starting from
	 * the root node. Should be used with care, as it is time-consuming, querying the database for
	 * every node in the Site Tree.
	 * 
	 * @return the nodes in scope from site tree
	 */
	public List<SiteNode> getNodesInScopeFromSiteTree() {
		List<SiteNode> nodes = new LinkedList<SiteNode>();
		SiteNode rootNode = (SiteNode) getSiteTree().getRoot();
		fillNodesInScope(rootNode, nodes);
		return nodes;
	}
	
	/**
	 * Fills a given list with nodes in scope, searching recursively.
	 * 
	 * @param rootNode the root node
	 * @param nodesList the nodes list
	 */
	private void fillNodesInScope(SiteNode rootNode, List<SiteNode> nodesList) {
		@SuppressWarnings("unchecked")
		Enumeration<SiteNode> en = rootNode.children();
		while (en.hasMoreElements()) {
			SiteNode sn = en.nextElement();
			if (isInScope(sn))
				nodesList.add(sn);
			fillNodesInScope(sn, nodesList);
		}
	}
	
	public List<String> getIncludeInScopeRegexs() {
		return includeInScopeRegexs;
	}
	
	private void checkRegexs (List<String> regexs) throws Exception {
	    for (String url : regexs) {
	    	url = url.trim();
	    	if (url.length() > 0) {
				Pattern.compile(url, Pattern.CASE_INSENSITIVE);
	    	}
	    }
	}

	public void setIncludeInScopeRegexs(List<String> includeRegexs) throws Exception {
		// Check they are all valid regexes first
		checkRegexs(includeRegexs);
		// Check if theyve been changed
		if (includeInScopeRegexs.size() == includeRegexs.size()) {
			boolean changed = false;
		    for (int i=0; i < includeInScopeRegexs.size(); i++) {
		    	if (! includeInScopeRegexs.get(i).equals(includeRegexs.get(i))) {
		    		changed = true;
		    		break;
		    	}
		    }
		    if (!changed) {
		    	// No point reapplying the same regexs
		    	return;
		    }
		}
		includeInScopeRegexs.clear();
		includeInScopePatterns.clear();
	    for (String url : includeRegexs) {
	    	url = url.trim();
	    	if (url.length() > 0) {
				Pattern p = Pattern.compile(url, Pattern.CASE_INSENSITIVE);
				includeInScopeRegexs.add(url);
				includeInScopePatterns.add(p);
	    	}
	    }
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_INCLUDE_IN_SCOPE, this.includeInScopeRegexs);
		refreshScope();
	}
	
	public void addIncludeInScopeRegex(String includeRegex) throws SQLException {
		Pattern p = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE);
		includeInScopePatterns.add(p);
		includeInScopeRegexs.add(includeRegex);
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_INCLUDE_IN_SCOPE, this.includeInScopeRegexs);
    	Control.getSingleton().sessionScopeChanged();
	}
	
	public List<String> getExcludeFromScopeRegexs() {
		return excludeFromScopeRegexs;
	}

	public void setExcludeFromScopeRegexs(List<String> excludeRegexs) throws Exception {
		// Check they are all valid regexes first
		checkRegexs(excludeRegexs);
		// Check if theyve been changed
		if (excludeFromScopeRegexs.size() == excludeRegexs.size()) {
			boolean changed = false;
		    for (int i=0; i < excludeFromScopeRegexs.size(); i++) {
		    	if (! excludeFromScopeRegexs.get(i).equals(excludeRegexs.get(i))) {
		    		changed = true;
		    		break;
		    	}
		    }
		    if (!changed) {
		    	// No point reapplying the same regexs
		    	return;
		    }
		}
		
		excludeFromScopeRegexs.clear();
		excludeFromScopePatterns.clear();
	    for (String url : excludeRegexs) {
	    	url = url.trim();
	    	if (url.length() > 0) {
				Pattern p = Pattern.compile(url, Pattern.CASE_INSENSITIVE);
				excludeFromScopePatterns.add(p);
				excludeFromScopeRegexs.add(url);
	    	}
	    }
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCOPE, this.excludeFromScopeRegexs);
		refreshScope();
	}

	public void addExcludeFromScopeRegex(String excludeRegex) throws SQLException {
		Pattern p = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE);
		excludeFromScopePatterns.add(p);
		excludeFromScopeRegexs.add(excludeRegex);
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCOPE, this.excludeFromScopeRegexs);
    	Control.getSingleton().sessionScopeChanged();
	}

	public void setExcludeFromProxyRegexs(List<String> ignoredRegexs) throws SQLException {
		this.excludeFromProxyRegexs = stripEmptyLines(ignoredRegexs);
		Control.getSingleton().setExcludeFromProxyUrls(this.excludeFromProxyRegexs);
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_PROXY, this.excludeFromProxyRegexs);
	}

	public List<String> getExcludeFromScanRegexs() {
		return excludeFromScanRegexs;
	}

	public void addExcludeFromScanRegexs(String ignoredRegex) throws SQLException {
		this.excludeFromScanRegexs.add(ignoredRegex);
		ExtensionActiveScan extAscan = 
			(ExtensionActiveScan) Control.getSingleton().getExtensionLoader().getExtension(ExtensionActiveScan.NAME);
		if (extAscan != null) {
			extAscan.setExcludeList(this.excludeFromScanRegexs);
		}
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCAN, this.excludeFromScanRegexs);
	}

	public void setExcludeFromScanRegexs(List<String> ignoredRegexs) throws SQLException {
		this.excludeFromScanRegexs = stripEmptyLines(ignoredRegexs);
		ExtensionActiveScan extAscan = 
			(ExtensionActiveScan) Control.getSingleton().getExtensionLoader().getExtension(ExtensionActiveScan.NAME);
		if (extAscan != null) {
			extAscan.setExcludeList(this.excludeFromScanRegexs);
		}
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SCAN, this.excludeFromScanRegexs);
	}

	public List<String> getExcludeFromSpiderRegexs() {
		return excludeFromSpiderRegexs;
	}

	public void addExcludeFromSpiderRegex(String ignoredRegex) throws SQLException {
		this.excludeFromSpiderRegexs.add(ignoredRegex);
		ExtensionSpider extSpider = 
			(ExtensionSpider) Control.getSingleton().getExtensionLoader().getExtension(ExtensionSpider.NAME);
		if (extSpider != null) {
			extSpider.setExcludeList(this.excludeFromSpiderRegexs);
		}
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SPIDER, this.excludeFromSpiderRegexs);
	}


	public void setExcludeFromSpiderRegexs(List<String> ignoredRegexs) throws SQLException {
		this.excludeFromSpiderRegexs = stripEmptyLines(ignoredRegexs);
		ExtensionSpider extSpider = 
			(ExtensionSpider) Control.getSingleton().getExtensionLoader().getExtension(ExtensionSpider.NAME);
		if (extSpider != null) {
			extSpider.setExcludeList(this.excludeFromSpiderRegexs);
		}
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_SPIDER, this.excludeFromSpiderRegexs);
	}

	// ZAP: Added method
	public List<String> getExcludeFromWebSocketRegexs() {
		return excludeFromWebSocketRegexs;
	}

	public void addExcludeFromWebSocketRegex(String ignoredRegex) throws SQLException {
		excludeFromWebSocketRegexs.add(ignoredRegex);
		setExcludeFromWebSocketRegexs(excludeFromWebSocketRegexs);
	}

	// ZAP: Added method
	public void setExcludeFromWebSocketRegexs(List<String> ignoredRegexs) throws SQLException {
		this.excludeFromWebSocketRegexs = stripEmptyLines(ignoredRegexs);
		ExtensionWebSocket extWebSocket = 
			(ExtensionWebSocket) Control.getSingleton().getExtensionLoader().getExtension(ExtensionWebSocket.NAME);
		if (extWebSocket != null) {
			extWebSocket.setStorageBlacklist(ignoredRegexs);
		}
		model.getDb().getTableSessionUrl().setUrls(RecordSessionUrl.TYPE_EXCLUDE_FROM_WEBSOCKET, this.excludeFromWebSocketRegexs);
	}
}