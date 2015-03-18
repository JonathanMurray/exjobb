package wekaWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import util.NonThrowingFileWriter;
import util.Texts;
import citationContextData.CitingPaper;
import citationContextData.Dataset;
import citationContextData.Sentence;
import citationContextData.SentenceType;
import citationContextData.Text;


public class InstanceHandler {
	
	public static void writeToArffFile(List<SentenceInstance> instances, File arffFile){
		
		System.out.println("writeInstanceToFile - " + arffFile.getPath() + " ...");
		
		NonThrowingFileWriter writer = new NonThrowingFileWriter(arffFile);
		writer.write("@RELATION sentence\n");
		instances.get(0).features.keySet().stream().sorted().forEach(feature -> {
			writer.write("@ATTRIBUTE " + feature + " ");
			if(feature.contains("UNIGRAM") || feature.contains("BIGRAM") || feature.contains("TRIGRAM") || feature.equals(FeatureName.SENTENCE_NUMBER.toString())){
				writer.write("NUMERIC\n");
			}else if(feature.equals(FeatureName.TEXT.toString())){
				writer.write("STRING\n");
			}else{
				writer.write("{true, false}\n");
			}
		});
		writer.write("@ATTRIBUTE class {" + 
				SentenceType.IMPLICIT_REFERENCE + "," + 
				SentenceType.NOT_REFERENCE + "}\n");
		writer.write("@DATA\n");
		instances.forEach(instance -> {
			instance.features.entrySet().stream()
				.sorted((e1,e2)->e1.getKey().compareTo(e2.getKey()))
				.forEach(e -> {
					writer.write(e.getValue() + ",");
			});
			writer.write(instance.instanceClass + "\n");
		});
		writer.close();
	}
	
	/**
	 * Sentences that are explicit references are excluded
	 * @param dataset
	 * @return
	 */
	public static <T extends Text> List<SentenceInstance> createInstances(Dataset<T> dataset, boolean onlyText, boolean balanceData){
		List<SentenceInstance> instances = new ArrayList<SentenceInstance>();
		for(CitingPaper<T> citer : dataset.citers){
			for(int i = 0; i < citer.sentences.size(); i++){
				Sentence<T> previous = i > 0 ? citer.sentences.get(i-1) : null;
				Sentence<T> sentence = citer.sentences.get(i);
				Sentence<T> next = i < citer.sentences.size() - 1? citer.sentences.get(i+1) : null;
				Map<String, Comparable<?>> features = extractFeatures(previous, sentence, next, dataset, onlyText, i);
				if(sentence.type == SentenceType.EXPLICIT_REFERENCE){ //TODO
					continue; //Excluded
				}
				instances.add(new SentenceInstance(features, sentence.type));
			}
		}
		if(balanceData){
			balanceData(instances);
		}
		System.out.println("extractInstances - done");
		return instances;
	}
	
	public static void balanceData(List<SentenceInstance> data){
		//TODO should shuffle data?
		int numTotal = data.size();
		long numPos = data.stream().filter(i -> i.instanceClass == SentenceType.IMPLICIT_REFERENCE).count();
		int q = (int)(numTotal / (double)numPos);
		
		int i = 0;
		Iterator<SentenceInstance> it = data.iterator();
		while(it.hasNext()){
			if(it.next().instanceClass == SentenceType.NOT_REFERENCE){
				if(i < q){
					it.remove();
					i++;
				}else{
					i = 0;
				}
			}
		}
	}
	
	private static <T extends Text> Map<String, Comparable<?>> extractFeatures(Sentence<T> previous, Sentence<T> sentence, Sentence<T> next, Dataset<T> dataset, boolean onlyText, int sentenceNumber){
		Texts texts = Texts.instance();
		Map<String, Comparable<?>> features = new HashMap<String, Comparable<?>>();
		List<String> rawWords = sentence.text.rawWords;
		String[] prevWords = previous != null? previous.text.rawWords.toArray(new String[0]) : new String[0];
		if(!onlyText){
			features.put(FeatureName.DET_WORK.toString(), texts.containsDetWork(rawWords));
			features.put(FeatureName.PRONOUN.toString(), texts.startsWith3rdPersonPronoun(rawWords));
			features.put(FeatureName.CONNECTOR.toString(), texts.startsWithConnector(rawWords));
			features.put(FeatureName.AFTER_EXPLICIT.toString(), texts.containsExplicitCitation(Arrays.asList(prevWords), dataset.citedMainAuthor));
			features.put(FeatureName.AFTER_HEADING.toString(), previous != null ? texts.startsWithSectionHeader( previous.text.rawWords) : false);
			features.put(FeatureName.HEADING.toString(), texts.startsWithSectionHeader(rawWords));
			features.put(FeatureName.BEFORE_HEADING.toString(), next != null? texts.startsWithSectionHeader(next.text.rawWords) : false);
			features.put(FeatureName.CONTAINS_AUTHOR.toString(), texts.containsMainAuthor(rawWords, dataset.citedMainAuthor));
			features.put(FeatureName.CONTAINS_ACRONYM.toString(), texts.containsAcronymWithIndex(rawWords, dataset.getAcronyms()));
			features.put(FeatureName.CONTAINS_LEXICAL_HOOK.toString(), texts.containsHookWithIndex(sentence.text.raw, dataset.getLexicalHooks()));
		}
		features.put(FeatureName.SENTENCE_NUMBER.toString(), sentenceNumber);
		features.put(FeatureName.TEXT.toString(), "'" + sentence.text.raw + "'");
		return features;
	}

}
