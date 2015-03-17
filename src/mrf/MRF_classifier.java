package mrf;

import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.map.hash.TIntDoubleHashMap;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.ClassificationResultImpl;
import util.Printer;
import util.Texts;
import util.Timer;
import citationContextData.CitingPaper;
import citationContextData.Dataset;
import citationContextData.Sentence;
import citationContextData.SentenceType;
import citationContextData.Text;


public class MRF_classifier<T extends Text> {
	
	protected static Printer printer = new Printer(true);
	private static final double DELTA = 0.02;
	private static final int MAX_RUNS = 10;
	private static final int NO = 0;
	private static final int YES = 1;
	
	protected final MRF_params params;
	protected Dataset<T> data;
	protected CitingPaper<T> currentCiter;
	
	private double minSimilarity;
	private double maxSimilarity;
	
	private double minNeighbourSimilarity;
	private double maxNeighbourSimilarity;
	
	private List<TIntDoubleHashMap> relatednessMemoization;
	protected List<double[]> selfBeliefs;
	protected List<Map<Integer,double[]>> allReceivedMessages;
	
	public MRF_classifier(MRF_params params){
		this.params = params;
	}
	
	public ClassificationResultImpl classify(Dataset<T> dataset){
		ClassificationResultImpl result = new ClassificationResultImpl();
		System.out.println("ACRONYMS: " + dataset.getAcronyms());
		System.out.println("HOOKS: " + dataset.getLexicalHooks()); 
		printer.print("MRF classifying citers: ");
		for(int i = 0; i < dataset.citers.size(); i++){
			printer.progress(i, 1);
			result.add(classifyOneCiter(i, dataset));
//			if(i == 3){
//				break; //TODO
//			}
		}
		printer.println("");
		return result;
	}
	
	public ClassificationResultImpl classifyOneCiter(int citerIndex, Dataset<T> dataset){
		Timer t = new Timer();
		setup(citerIndex, dataset);
		initMessages();
		for(int i = 0; i < MAX_RUNS; i++){ 
			boolean anyChange = iterate();
			if(!anyChange){
//				printer.println("Done after " + i + " iterations.");
				break;
			}
		}
		return getResults(params.beliefThreshold, t.getMillis());
	}
	
	private void setup(int citerIndex, Dataset<T> dataset){
		
		this.data = dataset;
		this.currentCiter = dataset.citers.get(citerIndex);
		
		int numSentences = currentCiter.sentences.size();
		
		relatednessMemoization = new ArrayList<TIntDoubleHashMap>();
		for(int i = 0; i < numSentences; i++){
			relatednessMemoization.add(new TIntDoubleHashMap());
		}
		
		setupRelatednessNormalization();
		
		TDoubleArrayList similarities = getSimilarities(dataset.citedContent, dataset.citedTitle);
		
		selfBeliefs = new ArrayList<double[]>();
		List<Double> unnormalizedBeliefs = new ArrayList<Double>();
		for(int i = 0; i < numSentences; i++){
			T text = currentCiter.sentences.get(i).text;
			double similarToCited = similarities.get(i);
			double unnormalizedBelief = selfBelief(text, dataset.citedMainAuthor, similarToCited, dataset.getAcronyms(), dataset.getLexicalHooks());
			unnormalizedBeliefs.add(unnormalizedBelief);
		}
		
		double maxBelief = Double.MIN_VALUE;
		double minBelief = Double.MAX_VALUE;
		int maxIndex = 0;
		for(int i = 0; i < numSentences; i++){
			if(currentCiter.sentences.get(i).type == SentenceType.EXPLICIT_REFERENCE){
				continue; // Don't let explicit citations raise the max-belief
			}
			double unnormalized = unnormalizedBeliefs.get(i);
			if(unnormalized < minBelief){
				minBelief = unnormalized;
			}
			if(unnormalized > maxBelief){
				maxBelief = unnormalized;
				maxIndex = i;
			}
		}
		
		for(int i = 0; i < numSentences; i++){
			double unnormalizedBelief = unnormalizedBeliefs.get(i);
			double normalized;
			if(currentCiter.sentences.get(i).type == SentenceType.EXPLICIT_REFERENCE){
				normalized = 1.0; //TODO set explicit sentences to 100% probability
			}else{
				if(maxBelief > minBelief){
					normalized = (unnormalizedBelief - minBelief) / (maxBelief - minBelief);
				}else{
					System.out.println(maxBelief + " !> " + minBelief);
					normalized = 0.5;
				}
				final double MIN_ALLOWED = 0.3; //TODO
				if(normalized < MIN_ALLOWED){
					normalized = MIN_ALLOWED; 
				}
			}
			selfBeliefs.add(new double[]{1 - normalized, normalized});
		}
	}
	
	private void setupRelatednessNormalization(){
		minNeighbourSimilarity = Double.MAX_VALUE;
		maxNeighbourSimilarity = Double.MIN_VALUE;
		int numSentences = currentCiter.sentences.size();
		for(int from = 0; from < numSentences; from++){
			int leftmostNeighbour = Math.max(0, from - params.neighbourhood);
			int rightmostNeighbour = Math.min(numSentences - 1, from + params.neighbourhood);
			for(int to = leftmostNeighbour; to <= rightmostNeighbour; to++){
				if(to != from){
					T fromText = currentCiter.sentences.get(from).text;
					T toText = currentCiter.sentences.get(to).text;
					double rel = fromText.similarity(toText);
					minNeighbourSimilarity = Math.min(minNeighbourSimilarity, rel);
					maxNeighbourSimilarity = Math.max(maxNeighbourSimilarity, rel);
				}
			}
		}
	}
	
	private TDoubleArrayList getSimilarities(T citedContent, T citedTitle){
		TDoubleArrayList similarities = new TDoubleArrayList();
		minSimilarity = Double.MAX_VALUE;
		maxSimilarity = Double.MIN_VALUE;
		for(Sentence<T> s : currentCiter.sentences){
			double sim = s.text.similarity(citedContent) + 2 * s.text.similarity(citedTitle);
			minSimilarity = Math.min(minSimilarity, sim);
			maxSimilarity = Math.max(maxSimilarity, sim);
			similarities.add(sim);
		}
		for(int i = 0; i < similarities.size(); i++){
			double normalized = (similarities.get(i) - minSimilarity) / (maxSimilarity-minSimilarity);
			similarities.set(i, normalized);
		}
		return similarities;
	}
	
	private double selfBelief(
			T sentence, 
			String authorLastName, 
			double similarToCited, 
			Set<String> acronyms,
			Set<String> lexicalHooks){
		
		double score = 0; 
		
		Printer p = new Printer(false);
		
		List<String> words = sentence.rawWords;
		
		p.println("\n\n" + sentence.raw); //TODO
		
		
//		if(Texts.instance().containsExplicitCitation(words, authorLastName)){
//			score +=  params.selfBelief.explicitCitWeight;
//		}
		
		p.println("Similarity: " + similarToCited);
		
		if(Texts.instance().containsMainAuthor(words, authorLastName)){
			score += params.selfBelief.authorWeight;
			p.println("author: " + params.selfBelief.authorWeight); //TODO
		}
//		if(Texts.instance().startsWithDetWork(words)){
//			score += params.selfBelief.detWorkWeight;
//		}
//		if(Texts.instance().startsWithLimitedDet(words)){
//			score += params.selfBelief.limitedDetWeight;
//		}
		score += similarToCited;
		if(Texts.instance().containsAcronyms(words, acronyms)){
			score += params.selfBelief.acronymWeight;
			p.println("acronyms: " + params.selfBelief.acronymWeight); //TODO
		}
		if(Texts.instance().containsLexicalHooks(words, lexicalHooks)){
			score += params.selfBelief.hooksWeight;
			p.println("hooks: " + params.selfBelief.hooksWeight); //TODO
		}
//		if(words.get(0).equals("It")){
//			score += params.selfBelief.itWeight;
//		}
		if(Texts.instance().startsWithSectionHeader(words)){
			score += params.selfBelief.headerWeight;
			p.println("header: " + params.selfBelief.headerWeight); //TODO
		}
		
		if(Double.isNaN(score)){
			throw new RuntimeException();
		}
		
		return score;
	}
	
	private ClassificationResultImpl getResults(double beliefThreshold, long passedMillis){
		int truePos = 0;
		int falsePos = 0;
		int trueNeg = 0;
		int falseNeg = 0;
		
		ArrayList<Integer> fpIndices = new ArrayList<Integer>();
		ArrayList<Integer> fnIndices = new ArrayList<Integer>();
		
		for(int i = 0; i < currentCiter.sentences.size(); i++){
			Sentence<T> sentence = currentCiter.sentences.get(i);
			double[] belief = finalBelief(i);
			if(sentence.type == SentenceType.EXPLICIT_REFERENCE){
				continue; //Don't count explicit citations in result!
			}

			DecimalFormat f = new DecimalFormat("#.##");
//			System.out.println( sentence.type + " (" + f.format(selfBeliefs.get(i)[1]) + " -> " + f.format(belief[1]) +  "):   " + sentence.text.raw); //TODO
			
			boolean closeToExplicit = false;
			for(int j = Math.max(0, i-2); j < Math.min(currentCiter.sentences.size()-1, i+2); j++){
				if(currentCiter.sentences.get(j).type==SentenceType.EXPLICIT_REFERENCE){
					closeToExplicit = true;
				}
			}
			
			boolean predictInContext = belief[1] > beliefThreshold && closeToExplicit;
			if(predictInContext){
				if(sentence.type == SentenceType.NOT_REFERENCE){
					
					fpIndices.add(i);
					falsePos ++;
				}else{
					truePos ++;
				}
			}else{
				if(sentence.type == SentenceType.NOT_REFERENCE){
					trueNeg ++;
				}else{
					Sentence prev = currentCiter.sentences.get(i-1);
					System.out.println();
					System.out.println(prev.type + "(" + f.format(selfBeliefs.get(i-1)[1]) + " -> " + f.format(finalBelief(i-1)[1]) +  "):   " + prev.text.raw);
					System.out.println("FN (" + f.format(selfBeliefs.get(i)[1]) + " -> " + f.format(belief[1]) +  "):   " + sentence.text.raw); //TODO
					System.out.println();
					fnIndices.add(i);
					falseNeg ++;
				}
			}
		}
		
		return new ClassificationResultImpl(truePos, falsePos, trueNeg, falseNeg, fpIndices, fnIndices, passedMillis);	}
	
	private double[] finalBelief(int sentence){
		double[] productReceived = productOfValues(allReceivedMessages.get(sentence));
		double[] belief = selfBeliefs.get(sentence);
		double[] totalBeliefAboutSelf = new double[]{
				belief[NO] * productReceived[NO], 
				belief[YES] * productReceived[YES]};
		normalizeProbabilityVector(totalBeliefAboutSelf);
		
		
		
//		Sentence<T> s = currentCiter.sentences.get(sentence);
//		if(s.type == SentenceType.IMPLICIT_REFERENCE || s.type == SentenceType.EXPLICIT_REFERENCE){
//			System.out.println(sentence + ". " + s.type + "\t" + s.text.rawWords);
//			NumberFormat f = new DecimalFormat("#0.00"); 
//			System.out.println(f.format(belief[1]) + " -> " + f.format(totalBeliefAboutSelf[1]));
//			System.out.println();
//		}
		
		
			
			
		return totalBeliefAboutSelf;
	}
	
	String beliefToString(double[] belief){
		NumberFormat formatter = new DecimalFormat("#0.00");     
		return formatter.format(belief[1]);
	}

	private void initMessages(){
		allReceivedMessages = new ArrayList<Map<Integer,double[]>>();
		int numSentences = currentCiter.sentences.size();
		for(int s = 0; s < numSentences; s++){
			Map<Integer, double[]> receivedMessages = new HashMap<Integer, double[]>();
			for(int m = Math.max(0, s-params.neighbourhood); m <= Math.min(s+params.neighbourhood, numSentences-1); m++){
				if(m != s){
					receivedMessages.put(m, new double[]{0.5,0.5}); //start value for msg
				}
			}
			allReceivedMessages.add(receivedMessages);
		}
	}
	
	private boolean iterate(){
		int numSentences = currentCiter.sentences.size();
		boolean anyChange = false;
		for(int from = 0; from < numSentences; from++){
			double[] belief = selfBeliefs.get(from);
			Map<Integer, double[]> receivedMessages = allReceivedMessages.get(from);
			int leftmostNeighbour = Math.max(0, from - params.neighbourhood);
			int rightmostNeighbour = Math.min(numSentences - 1, from + params.neighbourhood);
			for(int to = leftmostNeighbour; to <= rightmostNeighbour; to++){
				if(to != from){
					boolean msgChanged = sendMessage(from, to, receivedMessages, belief);
					if(msgChanged){
						anyChange = true;
					}
				}
			}
		}
		return anyChange;
	}
	
	private boolean sendMessage(int from, int to, Map<Integer, double[]> receivedMessages, double[] belief){
		double[] productReceived = productOfValuesExcept(receivedMessages, to);
		double[] totalBeliefAboutSelf = new double[]{
				belief[NO] * productReceived[NO], 
				belief[YES] * productReceived[YES]};
		normalizeProbabilityVector(totalBeliefAboutSelf);
		
		double[] message = new double[2];
		
		double[][] compatibility = new double[][]{
				compatibility(NO, from, to),
				compatibility(YES, from, to)
		};
		
		message[NO] =
				(totalBeliefAboutSelf[NO] * compatibility[NO][NO]) + 
				(totalBeliefAboutSelf[YES] * compatibility[YES][NO]);
		message[YES] =
				(totalBeliefAboutSelf[NO] * compatibility[NO][YES]) + 
				(totalBeliefAboutSelf[YES] * compatibility[YES][YES]);
		
		normalizeProbabilityVector(message);
		
		boolean msgChanged = false;
		if(allReceivedMessages.get(to).containsKey(from)){
			double[] prevMsg = allReceivedMessages.get(to).get(from);
			
			if(Math.abs(prevMsg[0] - message[0]) > DELTA || Math.abs(prevMsg[1] - message[1]) > DELTA){
				msgChanged = true;
			}
		}
		
		allReceivedMessages.get(to).put(from, message);
		return msgChanged;
	}
	
	private void normalizeProbabilityVector(double[] probabilities){
		if(probabilities.length != 2){
			throw new IllegalArgumentException();
		}
		double sum = probabilities[0] + probabilities[1];
		if(sum == 0){
			probabilities = new double[]{0.5,0.5};
		}else{
			probabilities[0] /= sum;
			probabilities[1] /= sum;
		}
	}
	
	private double[] productOfValues(Map<Integer,double[]> map){
		double[] prod = new double[]{1,1};
		for(int i : map.keySet()){
			prod[0] *= map.get(i)[0];
			prod[1] *= map.get(i)[1];
		}
		return prod;
	}
	
	private double[] productOfValuesExcept(Map<Integer,double[]> map, int exceptionKey){
		double[] prod = new double[]{1,1};
		for(int i : map.keySet()){
			if(i != exceptionKey){
				prod[0] *= map.get(i)[0];
				prod[1] *= map.get(i)[1];
			}
		}
		return prod;
	}
	
	private double[] compatibility(int context1, int s1, int s2){
		if(context1 == NO){
			return new double[]{0.5,0.5};
		}
		double relatedness = relatedness(s1, s2);
//		System.out.println(relatedness); //TODO
		double probSame = 1 / (1 + Math.exp( - relatedness)); //interval : [0.5 , 1]
//		probSame -= 0.1; //interval : [0.4 , 0.9]
//		System.out.println(probContext); //TODO
		return new double[]{1 - probSame, probSame};
	}
	
	private double relatedness(int s1, int s2){
		
		if(relatednessMemoization.get(s1).get(s2) != 0){
			return relatednessMemoization.get(s1).get(s2);
		}
		if(relatednessMemoization.get(s2).get(s1) != 0){
			return relatednessMemoization.get(s2).get(s1);
		}
		
		T t1 = currentCiter.sentences.get(s1).text;
		T t2 = currentCiter.sentences.get(s2).text;
		
		double relatedness = (t1.similarity(t2) - minNeighbourSimilarity) / (maxNeighbourSimilarity - minNeighbourSimilarity);;
		if(s2 == s1 + 1){
			relatedness += relatednessToPrevious(t2);
		}else if(s1 == s2 + 1){
			relatedness += relatednessToPrevious(t1);
		}
		relatednessMemoization.get(s1).put(s2, relatedness);
		return relatedness;
	}
	
	private double relatednessToPrevious(T text){
		
		if(Texts.instance().startsWithConnector(text.rawWords)){
			return 4;
		}
		
		if(Texts.instance().containsDetWork(text.rawWords)){
			return 3;
		}
		
		if(Texts.instance().startsWith3rdPersonPronoun(text.rawWords)){
			return 2;
		}
		
		if(text.rawWords.get(0).equals("It") || Texts.instance().containsDet(text.rawWords)){
			return 1.5;
		}
		
		return 1; //the fact they are next to each other
	}
	
}
