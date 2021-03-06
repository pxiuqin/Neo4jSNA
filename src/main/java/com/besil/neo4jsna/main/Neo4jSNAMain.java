package com.besil.neo4jsna.main;

import com.besil.neo4jsna.algorithms.*;
import com.besil.neo4jsna.algorithms.louvain.Louvain;
import com.besil.neo4jsna.algorithms.louvain.LouvainResult;
import com.besil.neo4jsna.engine.GraphAlgoEngine;
import com.besil.neo4jsna.measures.DirectedModularity;
import com.besil.neo4jsna.measures.UndirectedModularity;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.tooling.GlobalGraphOperations;

import java.io.*;
import java.util.Enumeration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class Neo4jSNAMain {
	public static void main(String[] args) {
        String zipFile = "data/cineasts_12k_movies_50k_actors_2.1.6.zip";
        String path = "data/cineasts_12k_movies_50k_actors.db/";

        try {
            FileUtils.deleteRecursively(new File(path));
            extractFolder(zipFile);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        long nodeCount, relsCount;
		
		// Open a database instance
        GraphDatabaseService g = new GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder(path)
                .setConfig(GraphDatabaseSettings.allow_store_upgrade, "true")
                .newGraphDatabase();
        try (Transaction tx = g.beginTx() ) {
			nodeCount = IteratorUtil.count( GlobalGraphOperations.at(g).getAllNodes() );
			relsCount = IteratorUtil.count( GlobalGraphOperations.at(g).getAllRelationships() );
			tx.success();
		}
		
		System.out.println("Node count: "+nodeCount);
        System.out.println("Rel count: " + relsCount);

        // Declare the GraphAlgoEngine on the database instance
		GraphAlgoEngine engine = new GraphAlgoEngine(g);
		if( args.length > 1 && args[1].equals("off") )
			engine.disableLogging();

        Louvain louvain = new Louvain(g);
        louvain.execute();
        LouvainResult result = louvain.getResult();
        for (int layer : result.layers()) {
            System.out.println("Layer " + layer + ": " + result.layer(layer).size() + " nodes");
        }

        LabelPropagation lp = new LabelPropagation();
        // Starts the algorithm on the given graph g
		engine.execute(lp);
		Long2LongMap communityMap = lp.getResult();
		long totCommunities = new LongOpenHashSet( communityMap.values() ).size();
        System.out.println("There are " + totCommunities + " communities according to Label Propagation");

		DirectedModularity modularity = new DirectedModularity(g);
		engine.execute(modularity);
        System.out.println("The directed modularity of this network is " + modularity.getResult());

        UndirectedModularity umodularity = new UndirectedModularity(g);
		engine.execute(umodularity);
        System.out.println("The undirected modularity of this network is " + umodularity.getResult());

        engine.clean(lp); // Now you can clean Label propagation results

        TriangleCount tc = new TriangleCount();
		engine.execute(tc);
		Long2LongMap triangleCount = tc.getResult();
		Optional<Long> totalTriangles = triangleCount.values().stream().reduce( (x, y) -> x + y );
		System.out.println("There are "+totalTriangles.get()+" triangles");

		PageRank pr = new PageRank(g);
		engine.execute(pr);
		Long2DoubleMap ranks = pr.getResult();
		engine.clean(pr);
		Optional<Double> res = ranks.values().parallelStream().reduce( (x, y) -> x + y );
		System.out.println("Check PageRank sum is 1.0: "+ res.get());

		ConnectedComponents cc = new ConnectedComponents();
		engine.execute(cc);
		Long2LongMap components = cc.getResult();
		engine.clean(cc);
		int totalComponents = new LongOpenHashSet( components.values() ).size();
		System.out.println("There are "+ totalComponents+ " different connected components");
		
		StronglyConnectedComponents scc = new StronglyConnectedComponents();
		engine.execute(scc);
		components = scc.getResult();
		engine.clean(scc);
		totalComponents = new LongOpenHashSet( components.values() ).size();
		System.out.println("There are "+ totalComponents+ " different strongly connected components");
		
		// Don't forget to shutdown the database
		g.shutdown();
	}

    static public void extractFolder(String zipFile) throws ZipException, IOException {
        System.out.println(zipFile);
        int BUFFER = 2048;
        File file = new File(zipFile);

        ZipFile zip = new ZipFile(file);
        String newPath = zipFile.substring(0, zipFile.length() - 4);

        new File(newPath).mkdir();
        Enumeration zipFileEntries = zip.entries();

        // Process each entry
        while (zipFileEntries.hasMoreElements()) {
            // grab a zip file entry
            ZipEntry entry = (ZipEntry) zipFileEntries.nextElement();
            String currentEntry = entry.getName();
            File destFile = new File(newPath, currentEntry);
            //destFile = new File(newPath, destFile.getName());
            File destinationParent = destFile.getParentFile();

            // create the parent directory structure if needed
            destinationParent.mkdirs();

            if (!entry.isDirectory()) {
                BufferedInputStream is = new BufferedInputStream(zip
                        .getInputStream(entry));
                int currentByte;
                // establish buffer for writing file
                byte data[] = new byte[BUFFER];

                // write the current file to disk
                FileOutputStream fos = new FileOutputStream(destFile);
                BufferedOutputStream dest = new BufferedOutputStream(fos,
                        BUFFER);

                // read and write until last byte is encountered
                while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
                    dest.write(data, 0, currentByte);
                }
                dest.flush();
                dest.close();
                is.close();
            }

            if (currentEntry.endsWith(".zip")) {
                // found a zip file, try to open
                extractFolder(destFile.getAbsolutePath());
            }
        }
    }
}
