/*  
 * $Id$
 * $URL$
 * This file is part of the program TFpredict. TFpredict performs the
 * identification and structural characterization of transcription factors.
 *  
 * Copyright (C) 2010-2014 Center for Bioinformatics Tuebingen (ZBIT),
 * University of Tuebingen by Johannes Eichner, Florian Topf, Andreas Draeger
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package modes;

import features.DomainFeatureGenerator;
import features.PercentileFeatureGenerator;
import io.AnimatedChar;
import io.BasicTools;
import io.ObjectRW;
import io.UniProtClient;
import ipr.IPRextract;
import ipr.IPRprocess;
import ipr.IPRrun;
import ipr.IprEntry;
import ipr.IprProcessed;
import ipr.IprRaw;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import liblinear.WekaClassifier;
import liblinear.WekaClassifier.ClassificationMethod;

import org.apache.commons.cli.CommandLine;

import resources.Resource;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.LibSVMLoader;

/**
 * 
 * @author Johannes Eichner
 * @version $Rev$
 * @since 1.0
 */
public class Predict {
	
	public static final int featureOffset = 10;
	
	private static final String interproPrefix = "http://www.ebi.ac.uk/interpro/ISearch?query=";
	private static final String transfacPublicURL = "http://www.gene-regulation.com/pub/databases.html";
	private static final String transfacClassURL = "http://www.gene-regulation.com/pub/databases/transfac/clSM.html";
	private static final int maxNumSequencesBatchMode = 10;
	
	private static final byte DuplicatedHeaderError = 1;
	private static final byte InvalidUniProtError = 2;
	private static final byte TooManySequencesError = 3;
	
	static Logger logger = Logger.getLogger(Predict.class.getName());
	static {
		logger.setLevel(Level.SEVERE);
	}

	
	// use webservice version by default (local version is used if argument "iprscanPath" is provided)
	static boolean useWeb = true;
	static boolean standAloneMode = false;
	static boolean batchMode = false;
	static boolean silent = true;
	static boolean useCharacteristicDomains = true;

	// static arguments required by TFpredict
	public static String iprpath = "";
	public static String blastpath = "";
	private static final int numBlastIter = 2;
	public static String tfClassifier_file = "models/tfPred/svmLinear.model";
	public static String superClassifier_file = "models/superPred/svmLinear.model";
	public static String relDomainsTF_file = "domainsTFpred.txt";
	public static String relDomainsSuper_file = "domainsSuperPred.txt";
	public static String characteristicTFdomains_file = "domainsTF.txt";
	public static String[] characteristicDomains_files = new String[] {
		"domainsClass0.txt", "domainsClass1.txt", "domainsClass2.txt", "domainsClass3.txt", "domainsClass4.txt"
	};
	public static String relGOterms_file = "DNA.go";
	public static String tfName2class_file = "transHMan";
	public static String tfPredBlastFasta = "blast_db/TFnonTF.fasta";
	public static String superPredBlastFasta = "blast_db/TF.fasta";
	public static String tfPredBlastDB = "blast_db/TFnonTF.db";
	public static String superPredBlastDB = "blast_db/TF.db";
	
	// arguments passed from Galaxy to TFpredict
	static String basedir = "";
	static String input_file = "";
	static String html_outfile;
	static String sabine_outfile;
	static String species;
	static String sequence;
	static String tfName = "Sequence_1";
	static String uniprot_id;
	static String fasta_file;
	
	private String tfnontfDBfastaFile;
	private String tfDBfastaFile;
	private Classifier tfClassifier;
	private Classifier superClassifier;
	private List<String> relDomains_TFclass;
	private List<String> relDomains_Superclass;
	private List<String> relGOterms;
	private Map<String,String> tfName2class;
	private Map<String, Integer> domain2tf;
	private Map<String, Integer> domain2superclass;
	
	private Map<String, IprEntry> seq2domain;
	private Map<String, IprRaw> IPRdomains;
	private Map<String, IprProcessed> seq2bindingDomain;
	
	// gfx related mapping of seqid to jobid
	private Map<String, String> seq2job;
	
	private String[] sequence_ids;
	private Map<String, String> sequences = new HashMap<String, String>();
	private Map<String, Double[]> probDist_TFclass  = new HashMap<String, Double[]>();
	private Map<String, Double[]> probDist_Superclass = new HashMap<String, Double[]>();
	private Map<String, Integer> predictedSuperclass  = new HashMap<String, Integer>();
	private Map<String, String> annotatedClass  = new HashMap<String, String>();
	private Map<String, String[]> bindingDomains  = new HashMap<String, String[]>();
	
	private Map<String, Boolean> predictionPossible = new HashMap<String, Boolean>();
	private Map<String, Boolean> predictionTrivial = new HashMap<String, Boolean>();
	private Map<String, Boolean> seqIsTF = new HashMap<String, Boolean>();
	private Map<String, Boolean> annotatedClassAvailable = new HashMap<String, Boolean>();
	private Map<String, Boolean> domainsPredicted = new HashMap<String, Boolean>();
	Map<String, Map<String, Double>> seq2blastHitsTF = new HashMap<String, Map<String, Double>>();
	Map<String, Map<String, Double>> seq2blastHitsSuper = new HashMap<String, Map<String, Double>>();
	
	public static final int Non_TF = 0;
	public static final int TF = 1;
	public static final int Basic_domain = 1;
	public static final int Zinc_finger = 2;
	public static final int Helix_turn_helix = 3;
	public static final int Beta_scaffold = 4;
	public static final int Other = 0;
	private static final String[] superclassNames = new String[] {"Other", "Basic domain", "Zinc finger", "Helix-turn-helix", "Beta scaffold"};

	
	static DecimalFormat df = new DecimalFormat("0.00");
	static {
		DecimalFormatSymbols symb = new DecimalFormatSymbols();
		symb.setDecimalSeparator('.');
		df.setDecimalFormatSymbols(symb);
	}

	public static void main(CommandLine cmd) throws Exception {
		
		Predict TFpredictor = new Predict();
		
		TFpredictor.parseArguments(cmd);
		TFpredictor.prepareInput();
		TFpredictor.prepareClassifiers();
		TFpredictor.runInterproScan();
		TFpredictor.runPsiBlast();
	    TFpredictor.performClassification();

	    if (standAloneMode) {
	    	TFpredictor.writeConsoleOutput();
	    } else {

	    	TFpredictor.writeHTMLoutput();
	    }
	    if (sabine_outfile != null) {
	    	TFpredictor.writeSABINEoutput();
	    }
	}
	
	/*
	public static void main(String[] args){
		testModelFiles();
	}
	*/
	
	public static void testModelFiles() {
		
		// read relevant domains for TF/non-TF classification
		List<String> relDomains = BasicTools.readResource2List("domainsTFpred.txt");
		//List<String> relDomains = BasicTools.readResource2List("domainsSuperPred.txt");
		
		String[] domains = new String[] {"IPR001646", "IPR004065", "IPR015310", "IPR000510", "IPR020956"};
		//String[] domains = new String[] {"IPR003604", "IPR008288", "IPR017970", "IPR010588", "IPR002546"};
		List<String> currDomains = new ArrayList<String>();
		currDomains.addAll(Arrays.asList(domains));
		
		String fvector = createIPRvector(currDomains, relDomains, featureOffset);
		int lastFeatureIdx = relDomains.size() + featureOffset;
		if (!fvector.endsWith(lastFeatureIdx + ":1")) {
			fvector += " " + lastFeatureIdx + ":0";
		}
		System.out.println("Feature vector: " + fvector);
	    Instance currInstance = getInst("0 " + fvector);
	    
		List<String> tfPredModelFiles = new ArrayList<String>();
		for (ClassificationMethod classMethod: ClassificationMethod.values()) {
			tfPredModelFiles.add("models/tfPred/" + classMethod.modelFileName);
			//tfPredModelFiles.add("models/superPred/" + classMethod.modelFileName);
		}
		
		for (int i=3; i<tfPredModelFiles.size(); i++) {
			try {
				Classifier tfPredictor = (Classifier) weka.core.SerializationHelper.read(Resource.class.getResourceAsStream(tfPredModelFiles.get(i)));
				double[] tfProb = tfPredictor.distributionForInstance(currInstance);
				
				System.out.print(tfPredModelFiles.get(i).replaceAll(".*/", "").replace(".model", "") + ": \t(" + tfProb[0]);
				for (int p=1; p<tfProb.length; p++) {
					System.out.print(",  " + df.format(tfProb[p]));
				}
				System.out.println(")");
	
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void parseArguments(CommandLine cmd) {
		
		if(cmd.hasOption("sequence")) {
			sequence = cmd.getOptionValue("sequence").replaceAll("\\s", "");
		}
		
		if(cmd.hasOption("species")) {
			species = cmd.getOptionValue("species");
		}
		
		if(cmd.hasOption("uniprotID")) {
			uniprot_id = cmd.getOptionValue("uniprotID");
		}
		
		if(cmd.hasOption("fasta")) {
			fasta_file = cmd.getOptionValue("fasta");
			batchMode = true;
		}
		
		if(cmd.hasOption("htmlOutfile")) {
			html_outfile = cmd.getOptionValue("htmlOutfile");
		}
		
		if(cmd.hasOption("sabineOutfile")) {
			sabine_outfile = cmd.getOptionValue("sabineOutfile");
		}
		
        if(cmd.hasOption("basedir")) {
            basedir = cmd.getOptionValue("basedir");
            if (!basedir.endsWith("/")) basedir += "/" ;
            input_file = basedir + "query.fasta";
        }
        
        if (cmd.hasOption("tfClassifier")) {
        	String tfClassifier = cmd.getOptionValue("tfClassifier");
        	String modelFileName = WekaClassifier.ClassificationMethod.valueOf(tfClassifier).modelFileName;
        	tfClassifier_file = "models/tfPred/" + modelFileName;
        }
        
        if (cmd.hasOption("superClassifier")) {
        	String superClassifier = cmd.getOptionValue("superClassifier");
        	String modelFileName = WekaClassifier.ClassificationMethod.valueOf(superClassifier).modelFileName;
        	superClassifier_file = "models/superPred/" + modelFileName;
        }
		
		if(cmd.hasOption("iprscanPath")) {
			iprpath = cmd.getOptionValue("iprscanPath");
			useWeb = false;
		}
		
		if (cmd.hasOption("ignoreCharacteristicDomains")) {
			useCharacteristicDomains = false;
		}
		
		// set BLAST path from argument (if given)
		if(cmd.hasOption("blastPath")) {
			blastpath = cmd.getOptionValue("blastPath");
				
		// set BLAST path from environment variable (if given)
		} else if (System.getenv("BLAST_DIR") != null && System.getenv("BLAST_DIR").length() > 0) {
			blastpath = System.getenv("BLAST_DIR");
		
		} else {
			System.out.println("TFpredict requires BLAST which is available from the NCBI FTP site\n" +
							   "(ftp://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/).\n" +
							   "After downloading a path to the local BLAST installation has to be passed to TFpredict.\n" +
							   "Please define the environment variable BLAST_DIR to point to the BLAST directory on\n" +
							   "your OS and run this program again.\n" +
							   "Alternatively you can use the command line argument -blastPath <pathToBlast>.\n");
			System.exit(0);
		}
		if (!blastpath.endsWith("/")) blastpath += "/";
		
		if(cmd.hasOption("standAloneMode")) {
			standAloneMode = true;
			silent = true;
		} else {
	    	FileHandler logFileHandler;
			try {
				logFileHandler = new FileHandler(sabine_outfile);
				logFileHandler.setFormatter(new Formatter() {
					@Override
					public String format(LogRecord record) {
						return record.getMessage();
					}
				});
				logger.addHandler(logFileHandler);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void prepareInput() {
		
		relDomains_TFclass = BasicTools.readResource2List(relDomainsTF_file);
		relDomains_Superclass = BasicTools.readResource2List(relDomainsSuper_file);
		tfName2class = (Map<String, String>) ObjectRW.readFromResource(tfName2class_file);
		
		relGOterms = BasicTools.readResource2List(relGOterms_file);
		
		// if UniProt ID was given --> retrieve sequence and species from UniProt
		if (uniprot_id != null) {
			UniProtClient uniprot_client = new UniProtClient();
			String fasta_seq = uniprot_client.getUniProtSequence(uniprot_id.toUpperCase(), true);
			
			// Stop, if given UniProt ID is invalid
			if (fasta_seq == null) {
				logger.log(Level.SEVERE, "Error. Invalid UniProt ID or Entry name: " + uniprot_id + ".");
				writeHTMLerrorOutput(InvalidUniProtError);
				System.exit(0);
			}
			
			String[] splitted_header = fasta_seq.substring(0, fasta_seq.indexOf(" ")).trim().split("\\|");
			
			tfName = splitted_header[2];
			uniprot_id = splitted_header[1];
			species = fasta_seq.substring(fasta_seq.indexOf("OS=")+3, fasta_seq.indexOf("GN=")-1);
			sequence = fasta_seq.replaceFirst(">.*\\n", "").replaceAll("\\n", "");
		} 
		
		// read characteristic domains (if desired)
		if (useCharacteristicDomains) {
			
			domain2tf = new HashMap<String, Integer>();
			List<String> tfDomains = BasicTools.readResource2List(characteristicTFdomains_file);
			for (String domainID: tfDomains) {
				domain2tf.put(domainID, TF);
			}
			
			domain2superclass = new HashMap<String, Integer>();
			for (int i=0; i<characteristicDomains_files.length; i++) {
				List<String> currDomains = BasicTools.readResource2List(characteristicDomains_files[i]);
				for (String domainID: currDomains) {
					domain2superclass.put(domainID, i);
				}
			}
		}
		
		if (batchMode) {
			// BatchMode --> parse sequences from given FASTA file (and shorten long headers)
			sequences = BasicTools.readFASTA(fasta_file);
			
			// Stop, if FASTA file contains duplicated headers
			if (sequences.containsKey(BasicTools.duplicatedHeaderKey)) {
				logger.log(Level.SEVERE, "Error. FASTA file contains duplicated headers.");
				writeHTMLerrorOutput(DuplicatedHeaderError);
				System.exit(0);
			}
			// Stop, if maximum number of sequences allowed for Batch mode was exceeded
			if ((sequences.size() > maxNumSequencesBatchMode) && !standAloneMode) {
				logger.log(Level.SEVERE, "Error. Maximum number of sequences allowed in Batch Mode: " + maxNumSequencesBatchMode + 
						   		   ". FASTA file contains " + sequences.size() + " sequences.");
				writeHTMLerrorOutput(TooManySequencesError);
				System.exit(0);
			}
			
			sequence_ids = sequences.keySet().toArray(new String[] {});
			BasicTools.writeFASTA(sequences, input_file);
			
		} else {
			// SingleQueryMode --> add default header and write protein sequence to file
			String[] inputSeq = BasicTools.wrapString(sequence);
			String[] fastaSeq = new String[inputSeq.length+1];
			fastaSeq[0] = ">" + tfName;
			sequence_ids = new String[] {tfName};
			for (int i=0; i<inputSeq.length; i++) {
				fastaSeq[i+1] = inputSeq[i];
			}
			BasicTools.writeArray2File(fastaSeq, input_file);
		}
	}
	
	private void prepareClassifiers() {

		// load TF/Non-TF and superclass classifier
		try {
			tfClassifier = (Classifier) weka.core.SerializationHelper.read(Resource.class.getResourceAsStream(tfClassifier_file));			 
			superClassifier = (Classifier) weka.core.SerializationHelper.read(Resource.class.getResourceAsStream(superClassifier_file));
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
    	if (!silent) {
    		//System.out.println("  " + relDomains_TFclass.size() + " domains used for TF/Non-TF classification.");
    		//System.out.println("  " + relDomains_Superclass.size() + " domains used for Superclass classification.\n");
    	}
	}
    
	 // execute iprscan and get results
	private void runInterproScan() {

		// HACK: line can be excluded for testing purposes
		IPRrun InterProScanRunner = new IPRrun(silent);
		AnimatedChar an = null;
		if (standAloneMode) {
			System.out.print("\n  Fetching domains from InterProScan. This may take several minutes... ");
			
			an = new AnimatedChar();
			//an.setOutputStream(System.out);
			//an.showAnimatedChar();
		}
		List<String[]> IPRoutput = InterProScanRunner.run(input_file, iprpath, basedir, useWeb, standAloneMode);
		seq2job = InterProScanRunner.getSeq2job();
		if (standAloneMode) {
			an.hideAnimatedChar();
			System.out.println();
		}
		
		// HACK: lines can be included for testing purposes
		//basedir = "/rahome/eichner/projects/tfpredict/failed_inputs/";
		//List<String[]> IPRoutput = BasicTools.readFile2ListSplitLines(basedir + "allTFs_iprscan_output.txt");
		
		// generates mapping from sequence IDs to InterPro domain IDs
		seq2domain = IPRextract.getSeq2DomainMap(IPRoutput);
		
		// generates map of from domain ID to object containing the InterPro ID, description, position, and GO classes
		IPRdomains = IPRextract.parseIPRoutput(IPRoutput);
	
		// process result
		seq2bindingDomain = IPRprocess.filterIPRdomains(seq2domain, IPRdomains, relGOterms, tfName2class);
		if (standAloneMode || !silent) {
			for	(String seq: sequence_ids) {
				System.out.println("\nProcessed " + seq + ":");
				int numDomains = 0;
				if (seq2domain.get(seq) != null) {
					numDomains = seq2domain.get(seq).domain_ids.size();
				}
				int numDomainsTFclass = 0;
				if (seq2domain.get(seq) != null) {
					numDomainsTFclass = BasicTools.intersect(seq2domain.get(seq).domain_ids, relDomains_TFclass).size();
				}
				int numDomainsSuperclass = 0;
				if (seq2domain.get(seq) != null) {
					numDomainsSuperclass = BasicTools.intersect(seq2domain.get(seq).domain_ids, relDomains_Superclass).size();
				}
				int numBindingDomains = 0;
				if (seq2bindingDomain.get(seq) != null) {
					numBindingDomains = seq2bindingDomain.get(seq).binding_domains.size();
				}
				System.out.println("  " + numDomains + " InterPro domain(s) found.");
				System.out.println("  " + numDomainsTFclass + " / " + numDomains + " InterPro domain(s) are relevant for TF/Non-TF classification.");
				System.out.println("  " + numDomainsSuperclass + " / " + numDomains + " InterPro domain(s) are relevant for Superclass prediction.");
				System.out.println("  " + numBindingDomains + " / " + numDomains + " InterPro domain(s) were identified as DNA-binding domain(s).");
			}
		}
	}
	
	// generates databases, runs BLAST on query sequence and extracts hits 
	private void runPsiBlast() {
		
		// copy FASTA files from Jar to temporary directory
		String blast_db_dir = basedir + "blast_db/";
		if (! new File(blast_db_dir).exists() && ! new File(blast_db_dir).mkdir()) {
			System.out.println("Error. Could not create directory for BLAST database.");
		}
		tfnontfDBfastaFile = blast_db_dir + new File(tfPredBlastFasta).getName();
		tfDBfastaFile = blast_db_dir + new File(superPredBlastFasta).getName();
		BasicTools.copy(tfPredBlastFasta, tfnontfDBfastaFile, true);
		BasicTools.copy(superPredBlastFasta, tfDBfastaFile, true);
		
		// generate PSI-BLAST databases from FASTA files
		String createDB_cmd = blastpath + "bin/makeblastdb";
		if (BasicTools.isWindows()) createDB_cmd = "\"" + createDB_cmd + "\"";
		String createDB_cmdTF = createDB_cmd +  " -in " + tfnontfDBfastaFile + " -out " + tfnontfDBfastaFile + ".db" + " -dbtype prot";
		String createDB_cmdSuper = createDB_cmd + " -in " + tfDBfastaFile + " -out " + tfDBfastaFile + ".db" + " -dbtype prot";
		BasicTools.runCommand(createDB_cmdTF , false);
		BasicTools.runCommand(createDB_cmdSuper, false);
		
		// blast query sequences against TF and TF/non-TF database
		String blastHitsFileTF = input_file.replace(".fasta", ".tf.hits");
		String blastHitsFileSuper = input_file.replace(".fasta", ".super.hits");
		
		// if given FASTA file contains multiple sequences --> split into single sequences
		Map<String, String> seq2fasta = new HashMap<String, String>();
		int seqCnt = 1;
		if (batchMode) {
			for (String seqID: sequence_ids) {
				String currFastaFile = input_file.replace(".fasta", ".seq" + seqCnt++ + ".fasta");
				BasicTools.writeFASTA(seqID, sequences.get(seqID), currFastaFile);
				seq2fasta.put(seqID, currFastaFile);
			}
		} else {
			seq2fasta.put(sequence_ids[0], input_file);
		}
			
		seqCnt = 1;
		for (String seqID: sequence_ids) {
			String runBLAST_cmd = blastpath + "bin/psiblast";
			if (BasicTools.isWindows()) runBLAST_cmd = "\"" + runBLAST_cmd + "\"";
			String currHitsFileTF = blastHitsFileTF;
			String currHitsFileSuper = blastHitsFileSuper;
			if (batchMode) {
				currHitsFileTF = blastHitsFileTF.replace(".tf.hits", ".seq" + seqCnt++ + ".tf.hits");
				currHitsFileSuper = blastHitsFileSuper.replace(".super.hits", ".seq" + seqCnt + ".super.hits");
			}
			String runBLAST_cmdTF = runBLAST_cmd + " -query " + seq2fasta.get(seqID) + " -num_iterations " + numBlastIter +  " -out " + currHitsFileTF + " -db " +  tfnontfDBfastaFile + ".db";
			String runBLAST_cmdSuper = runBLAST_cmd + " -query " + seq2fasta.get(seqID) + " -num_iterations " + numBlastIter +  " -out " + currHitsFileSuper + " -db " +  tfDBfastaFile + ".db";
			BasicTools.runCommand(runBLAST_cmdTF, false);
			BasicTools.runCommand(runBLAST_cmdSuper, false);
			seq2blastHitsTF.put(seqID, getBlastHits(currHitsFileTF));
			seq2blastHitsSuper.put(seqID, getBlastHits(currHitsFileSuper));
		}
	}
	
	// read PSI-BLAST output from temporary files
	private Map<String, Double> getBlastHits(String blastHitsFile) { 
			
		List<String> hitsTable = BasicTools.readFile2List(blastHitsFile, false);

			// skip header
			int lineIdx = 0;
			String line;
			while (lineIdx < hitsTable.size() && !(line = hitsTable.get(lineIdx)).startsWith("Sequences producing significant alignments")) {
				lineIdx++;
			}
			lineIdx = lineIdx + 2; 
			
			// read hits and corresponding bit scores
			Map<String, Double> blastHits = new HashMap<String, Double>();
			while (lineIdx < hitsTable.size() && !hitsTable.get(lineIdx).isEmpty() && !(line = hitsTable.get(lineIdx)).startsWith(">")) {

				StringTokenizer strtok = new StringTokenizer(line);
				String hitID = strtok.nextToken();
				String nextToken;
				while ((nextToken = strtok.nextToken()).startsWith("GO:"));  // skip GO terms in non-TF headers
				double hitScore = Double.parseDouble(nextToken); 
				blastHits.put(hitID, hitScore);
				lineIdx++;
			}
			return blastHits;
	}
	
	
	private void performClassification() {
    
		// check if trivial prediction is possible based on characteristic domains detected by InterProScan
		for (String seq: sequence_ids) {
			predictionTrivial.put(seq, false);
		}
		if (useCharacteristicDomains) {
			for (String seqID: seq2domain.keySet()) {
				ArrayList<String> currDomainIDs = seq2domain.get(seqID).domain_ids;
				for (String domainID: currDomainIDs) {
					if (domain2superclass.containsKey(domainID)) {
						int predSuperClass = domain2superclass.get(domainID);
						predictionTrivial.put(seqID, true);
						seqIsTF.put(seqID, true);
						probDist_TFclass.put(seqID, new Double[] {0.0, 1.0});
						predictedSuperclass.put(seqID, predSuperClass);
						Double[] superClassDist = new Double[] {0.0, 0.0, 0.0, 0.0, 0.0};
						superClassDist[predSuperClass] = 1.0;
						probDist_Superclass.put(seqID, superClassDist);
						break;
					}
				}
			}
		}
		
		// create Bit score percentile feature vectors
		Map<String, String> sequencesTF = BasicTools.readFASTA(tfnontfDBfastaFile, true);
		Map<String, String> sequencesSuper = BasicTools.readFASTA(tfDBfastaFile, true);
		Map<String, Integer> seq2labelTF = DomainFeatureGenerator.getLabelsFromFastaHeaders(sequencesTF.keySet(), false, false);
		Map<String, Integer> seq2labelSuper = DomainFeatureGenerator.getLabelsFromFastaHeaders(sequencesSuper.keySet(), true, false);
		Map<String, Instance> seq2percFeatTF = createPercentileFeatureVectors(seq2blastHitsTF, seq2labelTF, false);
		Map<String, Instance> seq2percFeatSuper = createPercentileFeatureVectors(seq2blastHitsSuper, seq2labelSuper, true);
		
		// flag all sequences for which no prediction is possible
		// (i.e., none of the IPRdomains which are relevant for TF/Non-TF classification was found)
		for (String seq: sequence_ids) {
			if (predictionTrivial.get(seq)) {
				predictionPossible.put(seq, true);
			} else {
				predictionPossible.put(seq, false);
			}
		}
		for (String seq: seq2percFeatTF.keySet()) {
			if (seq2percFeatTF.get(seq) != null) {
				predictionPossible.put(seq, true);
			}
		}
		
		// perform all classification steps if feature vector could be created
		try {
			for (String seq: seq2percFeatTF.keySet()) {
				if (predictionPossible.get(seq)) {				
					if (!predictionTrivial.get(seq)) {
						seqIsTF.put(seq, false);
					}
					annotatedClassAvailable.put(seq, false);
					domainsPredicted.put(seq, false);
					
					// perform TF/Non-TF classification
					if (!predictionTrivial.get(seq)) {
						Instance featVectorTF = seq2percFeatTF.get(seq);
						double[] currProbDistTF = tfClassifier.distributionForInstance(featVectorTF);
						if (currProbDistTF.length == 1) {
							currProbDistTF = new double[] {currProbDistTF[0], 1-currProbDistTF[0]};
						}
						probDist_TFclass.put(seq, BasicTools.double2Double(currProbDistTF));
						if (currProbDistTF[TF] >= currProbDistTF[Non_TF] && seq2percFeatSuper.containsKey(seq)) {
							seqIsTF.put(seq, true);
						} 
					}
					
					// if not yet identified as TF, try identification via characteristic domains
					if (!seqIsTF.get(seq) && useCharacteristicDomains) {
						IprEntry seq2DomainEntry = seq2domain.get(seq);
						if (seq2DomainEntry != null) {
							ArrayList<String> currDomainIDs = seq2DomainEntry.domain_ids;
							for (String domainID: currDomainIDs) {
								if (domain2tf.containsKey(domainID)) {
									seqIsTF.put(seq, true);
									probDist_TFclass.put(seq, new Double[] {0.0, 1.0});
									break;
								}
							}
						}
					}
		    		
					// if sequence was classified as TF --> predict superclass
					if (seqIsTF.get(seq)) {
						if (!predictionTrivial.get(seq)) {
							Instance featVectorSuper = seq2percFeatSuper.get(seq);
							double[] currProbDistSuper = superClassifier.distributionForInstance(featVectorSuper);
							probDist_Superclass.put(seq, BasicTools.double2Double(currProbDistSuper));

							int maxIndex = BasicTools.getMaxIndex(currProbDistSuper);
							predictedSuperclass.put(seq, maxIndex);
						}
						
						// predict DNA-binding domain
				    	IprProcessed ipr_res = seq2bindingDomain.get(seq);
				    	
				    	if (ipr_res != null) {
				    		if (!ipr_res.anno_transfac_class.isEmpty()) {
				    			annotatedClassAvailable.put(seq, true);
				    			annotatedClass.put(seq, ipr_res.anno_transfac_class);
				    		} 
				    		if (!ipr_res.binding_domains.isEmpty()) {
				    			domainsPredicted.put(seq, true);
				    			bindingDomains.put(seq, ipr_res.binding_domains.toArray(new String[]{}));
				    		}
				    	}
					}
				}
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	// writes HTML header
	private static void writeHTMLheader(BufferedWriter bw) {
		
		try {
			bw.write("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n");
			bw.write("     \"http://www.w3.org/TR/html4/loose.dtd\">\n");
			bw.write("<html>\n");
			bw.write("<head>\n");
			bw.write("<title>TF_predict Result</title>\n");
			bw.write("<style type=\"text/css\">\n");
			bw.write("  h1 { font-size: 150%;color: #002780; }\n");
			bw.write("  h2 { font-size: 135%;color: #002780; }\n");
			bw.write("  h4 { font-size: 135%;color: #990000; }\n");
			bw.write("  table { width: 300px; background-color: #E6E8FA; border: 1px solid black; padding: 3px; vertical-align: middle;}\n");
			bw.write("  th { font-weight: bold; padding-bottom: 4px; padding-top: 4px; text-align: center;}\n");
			bw.write("  td { padding-bottom: 4px; padding-top: 4px; text-align: center; background-color:#F8F8FF;}\n");
			bw.write("  td.win { padding-bottom: 4px; padding-top: 4px; text-align: center; background-color:#98FB98;}\n");
			bw.write("  td.draw { padding-bottom: 4px; padding-top: 4px; text-align: center; background-color:#F0E68C}\n");
			bw.write("  td.lose { padding-bottom: 4px; padding-top: 4px; text-align: center; background-color:#F8F8FF;}\n");
			bw.write("</style>\n");
			bw.write("</head>\n");
			bw.write("<body style=\"padding-left: 30px\">\n");
			
		} catch(IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	private void writeHTMLerrorOutput(byte errorType) {
		
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(new File(html_outfile)));
			
			writeHTMLheader(bw);
			bw.write("<h4> Error </h4>\n");
			
			if (errorType == InvalidUniProtError) {
				bw.write("<h3> Invalid UniProt ID or Entry name: " + uniprot_id + ".</h3>\n");
						
			} else if (errorType == TooManySequencesError) {
				bw.write("<h3> Maximum number of sequences allowed in Batch Mode: " + maxNumSequencesBatchMode + "<br>\n" +
			   		   "Number of sequences in given FASTA file: " + sequences.size() + "</h3>\n");
				
			} else if (errorType == DuplicatedHeaderError) {
				bw.write("<h3>FASTA file contains duplicated headers.</h3>\n");
			} 
			
			// close HTML file
			bw.write("</body>\n");
			bw.write("</html>\n");
			
			bw.flush();
			bw.close();
			
		} catch (Exception e) {
			System.exit(0);
			//e.printStackTrace();
		}
	}
	
	private void writeHTMLoutput() {
		
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(new File(html_outfile)));
			
			writeHTMLheader(bw);
		
			for (int i=0; i<sequence_ids.length; i++) {
				String seq = sequence_ids[i];
				
				if (i > 0) {
					bw.write("<br><hr>\n\n");
				}
				
				if (batchMode) {
					bw.write("<h1><span style=\"color:#000000\">Results report: </span>" + seq + "</h1>\n");
				}
				bw.write("<h2>TF/Non-TF prediction:</h2>\n");
				if (predictionPossible.get(seq)) {
					
					String[] outcomesTF = getClassificationOutcomes(BasicTools.Double2double(probDist_TFclass.get(seq)));
					bw.write("<table>\n");
					bw.write("  <tr><th></th><th>Probability<th></tr>\n");
					bw.write("  <tr><th> TF </th><td class=\"" + outcomesTF[TF] + "\"> " + df.format(probDist_TFclass.get(seq)[TF]) + " </td></tr>\n");
					bw.write("  <tr><th> Non-TF </th><td class=\"" + outcomesTF[Non_TF] + "\"> " + df.format(probDist_TFclass.get(seq)[Non_TF]) + " </td></tr>\n");
					bw.write("</table>\n\n");
					bw.write("<br>\n\n");
					    
					bw.write("<h2>Superclass prediction:</h2>\n");
					if (seqIsTF.get(seq)) {
						String[] outcomesSuper = getClassificationOutcomes(BasicTools.Double2double(probDist_Superclass.get(seq)));
						bw.write("<table>\n");
						bw.write("  <tr><th></th><th> Probability </th></tr>\n");
						bw.write("  <tr><th> Basic domain </th><td class=\"" + outcomesSuper[Basic_domain] + "\"> " + df.format(probDist_Superclass.get(seq)[Basic_domain]) + " </td></tr>\n");
						bw.write("  <tr><th> Zinc finger </th><td class=\"" + outcomesSuper[Zinc_finger] + "\"> " + df.format(probDist_Superclass.get(seq)[Zinc_finger]) + " </td></tr>\n");
						bw.write("  <tr><th> Helix-turn-helix </th><td class=\"" + outcomesSuper[Helix_turn_helix] + "\"> " + df.format(probDist_Superclass.get(seq)[Helix_turn_helix]) + " </td></tr>\n");
						bw.write("  <tr><th> Beta scaffold </th><td class=\"" + outcomesSuper[Beta_scaffold] + "\"> " + df.format(probDist_Superclass.get(seq)[Beta_scaffold]) + " </td></tr>\n");
						bw.write("  <tr><th> Other </th><td class=\"" + outcomesSuper[Other] + "\"> " + df.format(probDist_Superclass.get(seq)[Other]) + " </td></tr>\n");
						bw.write("</table>\n\n");
						bw.write("<br>\n\n");    	
						
						bw.write("<h2>Annotated structural class:</h2>\n");
			    		if (annotatedClassAvailable.get(seq)) {	
							bw.write("<h3>" + getAnnotatedSuperclass(annotatedClass.get(seq)) + " (<a href=\"" + transfacClassURL + "\" target=\"_blank\">" + annotatedClass.get(seq) + "</a>) </h3>\n");
							bw.write("The annotated structual class was obtained from the <a href=\"" + transfacPublicURL + "\" target=\"_blank\">TRANSFAC Public</a> database.\n");
							bw.write("<br><br><br>\n\n");
						} else {
				    		bw.write("<h3>Not available</h3>\n");
				    		bw.write("The annotated structural class could not be obtained from the <a href=\"" + transfacPublicURL + "\" target=\"_blank\">TRANSFAC Public</a> database.");
				    		bw.write("<br><br><br>\n\n");
				    	}
					    
			    		// include result image from InterProScan into HTML report
					    if (!seq2job.isEmpty() && seq2job.containsKey(seq)) {
					    	String job = seq2job.get(seq);
							bw.write("<table>\n");
							bw.write("  <tr><th> <img src=\"" + job + ".svg.svg\"/>" + "</th></tr>\n");	
							bw.write("  <tr><th> Illustration generated by <a href=https://www.ebi.ac.uk/Tools/services/web/toolresult.ebi?jobId="+job+"&tool=iprscan&analysis=visual target=\"_blank\"> InterProScan </a> </th></tr>\n");
							bw.write("</table>\n\n");
							bw.write("<br>\n\n");
					    }
			    		bw.write("<h2>DNA-binding domain(s):</h2>\n");
					    if (domainsPredicted.get(seq)) {
							bw.write("<table>\n");
							bw.write("  <tr><th> Domain ID </th><th> Start </th><th> End </th></tr>\n");	
					    	
							for (String domain : bindingDomains.get(seq)) {
								String[] splitted_domain = domain.replace("    ", "\t").split("\t");
								String currLink =  "<a href=\"" + interproPrefix + splitted_domain[0] + "\" target=\"_blank\"> " + splitted_domain[0] + " </a>";
								bw.write("  <tr><td> "+ currLink + " </td><td> "+ splitted_domain[1] +" </td><td> " + splitted_domain[2] +" </td></tr>\n"); 
							}
							bw.write("</table>\n\n");
							bw.write("<br>\n\n");
							
					    } else {
				    		bw.write("<h2>No DNA-binding domain found.</h2>\n");
				    	}
					    
				    // if sequence was classified as Non-TF --> display message 
					} else {
						bw.write("<h3>No prediction possible.</h3>");
				    	bw.write("The given sequence was classified as a Non-TF. As all further classification steps (e.g., superclass and DNA-binding domain prediction) require a TF sequence, these steps were not performed.");
					}
			    } else {
			    	bw.write("<h3>No prediction possible.</h3>");
			    	bw.write("BLAST did not find any significant hits in the protein sequence database. Consequently, TFpredict could not perform the prediction task.");
			    }
			}
			
			// close HTML file
			bw.write("</body>\n");
			bw.write("</html>\n");
			
			bw.flush();
			bw.close();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	private void writeConsoleOutput() {
		
		String hline = "  -----------------------";
		
		for (int i=0; i<sequence_ids.length; i++) {
			String seq = sequence_ids[i];
			
			if (i > 0) {
				System.out.println("__________________________________________");
			}
			
			System.out.println("\n==========================================");
			System.out.println("Results report for sequence: " + seq);
			System.out.println("==========================================\n");
			
			if (predictionPossible.get(seq)) {
				System.out.println("  TF/Non-TF prediction:");
				System.out.println(hline);
				System.out.println("                Probability");
				System.out.println("  TF            " + df.format(probDist_TFclass.get(seq)[TF]));
				System.out.println("  Non-TF        " + df.format(probDist_TFclass.get(seq)[Non_TF]) + "\n");

				if (seqIsTF.get(seq)) {
					System.out.println("  Superclass prediction:");
					System.out.println(hline);
					System.out.println("                      Probability");
					System.out.println("  Basic domain        " + df.format(probDist_Superclass.get(seq)[Basic_domain]));
					System.out.println("  Zinc finger         " + df.format(probDist_Superclass.get(seq)[Zinc_finger]));
					System.out.println("  Helix-turn-helix    " + df.format(probDist_Superclass.get(seq)[Helix_turn_helix]));
					System.out.println("  Beta scaffold       " + df.format(probDist_Superclass.get(seq)[Beta_scaffold]));
					System.out.println("  Other               " + df.format(probDist_Superclass.get(seq)[Other]) + "\n");
					
					if (annotatedClassAvailable.get(seq)) {	
						System.out.println("  Annotated structural class:");
						System.out.println(hline);
						System.out.println("  " + getAnnotatedSuperclass(annotatedClass.get(seq)) + " (" + annotatedClass.get(seq) + ") \n");
					}
					
					if (domainsPredicted.get(seq)) {
						System.out.println("  DNA-binding domain(s):");
						System.out.println(hline);
						System.out.println("  Domain ID \t Start \t End");
						for (String domain : bindingDomains.get(seq)) {
							String[] splitted_domain = domain.replace("    ", "\t").split("\t");
							System.out.println("  " + splitted_domain[0] + " \t " + splitted_domain[1] + " \t " + splitted_domain[2]); 
						}
					
					} else {
						System.out.println("  DNA-binding domain could not be predicted.\n");
					}
				}
				
			} else {
				System.out.println("  No prediction possible.\n");
			}
		}
	}
	
	private void writeSABINEoutput() {
			
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(new File(sabine_outfile)));
			
			for (int i=0; i<sequence_ids.length; i++) {
				String seq = sequence_ids[i];
				
				if (i > 0) {
					bw.write("//\nXX\n");
				}
				
				if (batchMode) {
					bw.write("NA  " + seq + "\n");
				} else {
					bw.write("NA  " + tfName + "\n");
				}
				bw.write("XX  \n");
				bw.write("SP  " + species + "\n");
				bw.write("XX  \n");
				if (uniprot_id != null) {
					bw.write("RF  " + uniprot_id + "\n");
					bw.write("XX  \n");
				}
				
				if (predictionPossible.get(seq) && seqIsTF.get(seq)) {
					
					if (annotatedClassAvailable.get(seq)) {
						bw.write("CL  " + expandTransfacClass(annotatedClass.get(seq)) + "\n");
					} else {
						bw.write("CL  " + predictedSuperclass.get(seq) + ".0.0.0.0" + "\n");
					}
					bw.write("XX  \n");
		
					// write sequence
					String[] wrapped_seq;
					if (batchMode) {
						wrapped_seq = BasicTools.wrapString(sequences.get(seq));
					} else {
						wrapped_seq = BasicTools.wrapString(sequence);
					}
					for (String line: wrapped_seq) {
						bw.write("S1  " + line + "\n"); 
					}
					bw.write("XX  \n");
							
					// write domains
					if (domainsPredicted.get(seq)) {
						for (String domain : bindingDomains.get(seq)) {
							bw.write("FT  " + domain + "\n");
						}
						bw.write("XX\n");
					}
					
				// Protein was either not classified (no IPR domains found) or classified as Non-TF
				} else {
					if (predictionPossible.get(seq)) {
						bw.write("CL  Non-TF\nXX\n");
					} else {
						bw.write("CL  Unknown\nXX\n");
					}
				}
			}
			bw.flush();
			bw.close();
			
		} catch(IOException ioe) {
			System.out.println(ioe.getMessage());
			System.out.println("IOException occurred while writing input file for SABINE.");
		}
	}
	
	// returns "win", "lose", or "draw" for each class depending on the given probabilities
	private static String[] getClassificationOutcomes(double[] probDist) {
		
		String[] classOutcome = new String[probDist.length];
		double maxProb = BasicTools.getMax(probDist);
		List<Integer> winIdx = new ArrayList<Integer>();
		int numWinners = 0;
		
		for (int i=0; i<probDist.length; i++) {
			if (probDist[i] == maxProb) {
				classOutcome[i] = "win";
				winIdx.add(i);
				numWinners++;
			} else {
				classOutcome[i] = "lose";
			}
		}
		
		// multiple winning classes -> set outcome for winning classes to "draw" 
		if (numWinners > 1) {
			for (int i=0; i<winIdx.size(); i++) {
				classOutcome[winIdx.get(i)] = "draw";
			}
		}
		return classOutcome;
	}
	
   // convert transfac-classification to SABINE-InputFileFormat                                                                                                                                 
    private static String expandTransfacClass(String transfac_class) {
            String expanded_class = transfac_class + ".";

            while (expanded_class.length() <= 9) {
            	expanded_class = expanded_class.concat("0.");
            }

            return expanded_class;
    }

    private static String getAnnotatedSuperclass(String transfac_class) {
    	return(superclassNames[Integer.parseInt(transfac_class.substring(0,1))]);
    }
	
	/**
	 * 
	 * @param predIPRdomains
	 * @param relIPRdomains
	 * @param start
	 * @return
	 */
	public static String createIPRvector(List<String> predIPRdomains, List<String> relIPRdomains, int start) {
		String fvector = "";
		
		for (int i = 0; i < relIPRdomains.size(); i++) {
			String curr_ipr = relIPRdomains.get(i);
			if (predIPRdomains.contains(curr_ipr)) {
				
				int column = i+1+start;
				fvector = fvector.concat(column + ":" + "1 ");
			}
		}
		return fvector.trim();
	}
	
	/*
	 * function used to create the bit score percentile feature vectors for TF/non-TF and superclass prediction
	 */
	private static Map<String, Instance> createPercentileFeatureVectors(Map<String, Map<String, Double>> seq2blastHits, Map<String, Integer> seq2label, boolean superPred) {
		
		PercentileFeatureGenerator percFeatGen = new PercentileFeatureGenerator(seq2blastHits, seq2label, superPred);
		percFeatGen.computeFeaturesFromBlastResult();
		Map<String, double[]> seq2feat = percFeatGen.getFeatures();
		
		Map<String, Instance> seq2fvector = new HashMap<String, Instance>();
		for  (String seqID: seq2feat.keySet()) {
			String currFeatVec = BasicTools.doubleArrayToLibSVM(seq2feat.get(seqID));
			seq2fvector.put(seqID, getInst("0 " + currFeatVec));
		}
		return seq2fvector;
	}

	private static Instance getInst(String fvector) {

		Instance inst = null;
	
		LibSVMLoader lsl = new LibSVMLoader();
		
		Instances tmp = null;
		InputStream is;
		try {
			is = new ByteArrayInputStream(fvector.getBytes("UTF-8"));
			lsl.setSource(is);
			tmp = lsl.getDataSet();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		inst = tmp.firstInstance();
		
		return inst;
	}
}
