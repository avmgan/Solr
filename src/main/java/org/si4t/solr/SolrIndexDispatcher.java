/**
 * Copyright 2011-2013 Radagio & SDL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.si4t.solr;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.HttpClient;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.ContentStreamBase.FileStream;
import org.apache.solr.core.CoreContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.tridion.storage.si4t.BinaryIndexData;
import com.tridion.storage.si4t.Utils;

/**
 * SolrIndexDispatcher.
 * 
 * Singleton. Dispatches updates to the Solr Index.
 * 
 * @author R.S. Kempees
 * @version 1.20
 * @since 1.00
 */
public enum SolrIndexDispatcher
{
	INSTANCE;
	private static ConcurrentHashMap<String, SolrServer> _solrServers = new ConcurrentHashMap<String, SolrServer>();
	private static ConcurrentHashMap<String, CoreContainer> _solrContainers = new ConcurrentHashMap<String, CoreContainer>();
	private static ConcurrentHashMap<String, HttpClient> _httpClients = new ConcurrentHashMap<String, HttpClient>();
	private static Logger log = LoggerFactory.getLogger(SolrIndexDispatcher.class);

	private SolrServer getSolrServer(SolrClientRequest clientRequest) throws ParserConfigurationException, IOException, SAXException
	{
		switch (clientRequest.getServerMode())
		{
			case EMBEDDED:
				if (_solrServers.get(clientRequest.getSearcherId()) == null)
				{
					log.info("Obtaining Embedded Solr server [(" + clientRequest.getSearcherId() + "): (" + clientRequest.getSolrHome() + "),(" + clientRequest.getSolrCore() + ")]");
					this.createEmbeddedSolrServer(clientRequest.getSearcherId(), clientRequest.getSolrHome(), clientRequest.getSolrCore());
				}
				return _solrServers.get(clientRequest.getSearcherId());

			case HTTP:
				if (_solrServers.get(clientRequest.getSolrUrl()) == null)
				{
					log.info("Obtaining Http Solr server [" + clientRequest.getSolrUrl() + ": " + clientRequest.getSolrUrl());
					this.createHttpSolrServer(clientRequest.getSolrUrl());

				}
				return _solrServers.get(clientRequest.getSolrUrl());

		}
		return null;
	}

	/**
	 * Creates the embedded solr server.
	 * 
	 * Use this only in special cases to for instance to first time indexing.
	 * 
	 * @deprecated
	 * @param searcherId
	 * @param solrHome
	 * @param coreName
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws SAXException
	 */
	@Deprecated
	private void createEmbeddedSolrServer(String searcherId, String solrHome, String coreName) throws ParserConfigurationException, IOException, SAXException
	{
		File home = new File(solrHome);
		File solrConfig = new File(home, "solr.xml");
		CoreContainer coreContainer = new CoreContainer();
		coreContainer.load(solrHome, solrConfig);
		EmbeddedSolrServer server = new EmbeddedSolrServer(coreContainer, coreName);

		_solrServers.put(searcherId, server);
		_solrContainers.put(searcherId, coreContainer);

		log.info("Created an Embedded Solr server client instance for " + searcherId + ", " + solrHome + ", " + coreName);
	}

	private void createHttpSolrServer(String url)
	{
		if (_httpClients.get(url) == null)
		{
			HttpSolrServer server = new HttpSolrServer(url);
			server.setDefaultMaxConnectionsPerHost(100);
			HttpClient client = server.getHttpClient();
			log.debug(">> Creating HttpClient instance");
			_httpClients.put(url, client);
			_solrServers.put(url, server);
		}
		else
		{
			log.debug(">> Reusing existing HttpClient instance");
			HttpSolrServer server = new HttpSolrServer(url, _httpClients.get(url));
			server.setDefaultMaxConnectionsPerHost(100);
			_solrServers.put(url, server);
		}
		log.info("Created a Commons Http Solr server client instance for " + url);
	}

	public String addBinaries(ConcurrentHashMap<String, BinaryIndexData> binaryAdds, SolrClientRequest clientRequest) throws IOException, SolrServerException, ParserConfigurationException, SAXException
	{

		SolrServer server = null;

		server = this.getSolrServer(clientRequest);

		if (server == null)
		{
			throw new SolrServerException("Solr server not instantiated.");
		}
		StringBuilder rsp = new StringBuilder();
		String rspResponse = " path not found";

		for (Map.Entry<String, BinaryIndexData> entry : binaryAdds.entrySet())
		{
			BinaryIndexData data = entry.getValue();

			log.debug("Dispatching binary content to Solr.");

			FileStream fs = this.getBinaryInputStream(data);

			if (fs != null)
			{

				String id = data.getUniqueIndexId();
				log.info("Indexing binary with Id: " + id + ", and URL Path:" + data.getIndexUrl());
				ContentStreamUpdateRequest up = new ContentStreamUpdateRequest("/update/extract");

				up.addContentStream(fs);

				up.setParam("literal.id", id);
				up.setParam("literal.pubdate", "NOW");
				up.setParam("literal.url", data.getIndexUrl().replace(" ", "%20"));

				if (!Utils.StringIsNullOrEmpty(data.getFileSize()))
				{
					up.setParam("literal.fileSize", data.getFileSize());
				}
				if (!Utils.StringIsNullOrEmpty(data.getFileType()))
				{
					up.setParam("literal.fileType", data.getFileType());
				}
				up.setParam("defaultField", "binary_content");

				UpdateResponse serverrsp;

				serverrsp = up.process(server);
				rsp.append(serverrsp.getResponse());

				log.info("Committing adding binaries.");
				rsp.append("\n");

				serverrsp = server.commit();
				rsp.append(serverrsp.getResponse());

				rspResponse = rsp.toString();
			}
			else
			{
				log.error("Could not process binary: " + data.getIndexUrl());
			}
		}
		return ("Adding binaries had the following response: " + rspResponse);
	}

	private FileStream getBinaryInputStream(BinaryIndexData data) throws IOException
	{
		String fileName = Utils.GetBinaryFileName(data.getFileName());

		if (fileName.length() < 3)
		{
			fileName += "000";
		}

		String fileExtension = Utils.GetBinaryFileExtension(data.getFileName());
		File tempBinaryFile = null;
		OutputStream out = null;
		try
		{
			tempBinaryFile = File.createTempFile(fileName, fileExtension, null);
			log.trace("File created: " + tempBinaryFile.getAbsolutePath());
			tempBinaryFile.deleteOnExit();
			out = new FileOutputStream(tempBinaryFile);
			IOUtils.write(data.getContent().getContent(), out);
			log.trace("IOUtils is done writing binary content.");
		}
		catch (FileNotFoundException e)
		{
			this.logException(e);
			throw e;
		}
		catch (IOException e)
		{
			this.logException(e);
			throw e;
		}
		finally
		{
			tempBinaryFile.deleteOnExit();
			IOUtils.closeQuietly(out);
		}

		return new FileStream(tempBinaryFile);
	}

	private void logException(Exception e)
	{
		log.error(e.getMessage());
		log.error(Utils.stacktraceToString(e.getStackTrace()));
	}

	public String addDocuments(DispatcherPackage dispatcherPackage) throws ParserConfigurationException, IOException, SAXException, SolrServerException
	{
		SolrServer server = this.getSolrServer(dispatcherPackage.getRequest());
		if (server == null)
		{
			throw new SolrServerException("Solr server not instantiated.");
		}
		ArrayList<SolrInputDocument> documents = dispatcherPackage.getDocuments();

		if (documents == null)
		{
			throw new NullPointerException("Document list is null");
		}

		for (SolrInputDocument d : documents)
		{
			if (d == null || d.isEmpty())
			{
				log.error("Document is null Or empty");
			}
			else
			{
				log.info(Utils.RemoveLineBreaks(d.toString()));
				server.add(d);
			}
		}

		UpdateResponse serverrsp = server.commit(true, true);

		return ("Processing " + documents.size() + " documents had the following response: " + serverrsp.getResponse());
	}

	public String removeFromSolr(Set<String> ids, SolrClientRequest clientRequest) throws SolrServerException, IOException, ParserConfigurationException, SAXException
	{
		SolrServer server = this.getSolrServer(clientRequest);
		if (server == null)
		{
			throw new SolrServerException("Solr server not instantiated.");
		}
		ArrayList<String> idList = new ArrayList<String>(ids);
		for (String id : idList)
		{
			log.debug("Removing: " + id);
		}
		server.deleteById(idList);
		UpdateResponse serverrsp = server.commit();
		return ("Deleting " + ids.size() + " document(s) had the following response: " + serverrsp.getResponse());
	}

	public void destroyServers()
	{
		for (Entry<String, CoreContainer> entry : _solrContainers.entrySet())
		{
			CoreContainer c = entry.getValue();
			if (c != null)
			{
				log.info("Shutting down CoreContainer for searcher: " + entry.getKey());
				c.shutdown();
			}
		}

		for (Entry<String, HttpClient> clients : _httpClients.entrySet())
		{
			HttpClient client = clients.getValue();
			if (client != null)
			{
				log.info("Closing down HttpClient for url: " + clients.getKey());
				client.getConnectionManager().shutdown();
			}
		}
	}

}
