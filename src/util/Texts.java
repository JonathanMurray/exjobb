package util;

import gnu.trove.iterator.TObjectDoubleIterator;
import gnu.trove.map.hash.TObjectDoubleHashMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;


public class Texts {
	
	private List<String> determiners;
	private List<String> workNouns;
	private List<String> thirdPersonPronouns;
	private List<String> connectors;
	private HashSet<String> stopwords;
	
	public static final String NUMBER_TAG = "<NUMBER>";
	public static final String HEADER_PATTERN = "\\d+\\.\\d+.*";
	
	private static Texts instance;
	
	public static void main(String[] args) {
		System.out.println(split(" A B  C   ").collect(Collectors.toCollection(ArrayList::new)));
	}
	
	public static Texts instance(){
		if(instance == null){
			instance = new Texts();
		}
		return instance;
	}
	
	private Texts(){
		try {
			setup();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	private void setup() throws IOException{
		String dir = Environment.exjobbHome() + "/resources/wordLists/";
		determiners = readLines(dir + "/determinerWords.txt");
		workNouns = readLines(dir + "/workNouns.txt");
		thirdPersonPronouns = readLines(dir + "/thirdPersonPronouns.txt");
		connectors = readLines(dir + "/connectors.txt");
		stopwords = readLinesToSet(dir + "/stopwords.txt");
	}
	
	public List<String> removeStopwords(List<String> words){
		List<String> filtered = new ArrayList<String>();
		for(String w : words){
			if(!stopwords.contains(w)){
				filtered.add(w);
			}
		}
		return filtered;
	}
	
	public boolean isStopword(String word){
		return stopwords.contains(word);
	}
	
	/**
	 * Will remove useless characters before splitting
	 * @param text
	 * @return
	 */
	public static Stream<String> split(String text){
		String removeRegex = "\\(*\\)*\\[*\\]*\\{*\\}*\\.*\\,*\\:*\\;*\\\\*\\/*";
		text = text.replaceAll(removeRegex, "");
		int pos = text.indexOf(' ') + 1;
		Stream.Builder<String> words = Stream.builder();
		if(pos == 0){ //no spaces in input
			words.add(text);
			return words.build();
		}
		String word;
		
		word = text.substring(0, pos - 1);
		if(word.length() > 0){
    		words.add(word);
    	}
        int end;
        while ((end = text.indexOf(' ', pos)) >= 0) {
        	word = text.substring(pos, end);
        	if(word.length() > 0){
        		words.add(word);
        	}
            pos = end + 1;
        }
        if(pos < text.length()){
        	words.add(text.substring(pos, text.length()));
        }
        return words.build();
	}
	
	public static String merge(Collection<String> words){
		if(words.size() == 0){
			return "";
		}
		StringBuilder s = new StringBuilder();
		for(String word : words){
			s.append(word + " ");
		}
		return s.substring(0, s.length()-1);
	}
	
	private HashSet<String> readLinesToSet(String filePath) throws IOException{
		return Files.lines(Paths.get(filePath)).collect(Collectors.toCollection(HashSet::new));
	}
	
	private List<String> readLines(String filePath) throws IOException{
		return Files.lines(Paths.get(filePath)).collect(Collectors.toList());
	}
	
	/**
	 * @param f
	 * @param maxLines Is ignored if < 1
	 * @return
	 */
	public static String readTextFile(File f, int maxLines){
		try(Scanner sc = new Scanner(new BufferedReader(new FileReader(f)))) {
			StringBuilder s = new StringBuilder();
			int lineNumber = 0;
			while(sc.hasNextLine()){
				lineNumber ++;
				if(maxLines > 0 && lineNumber > maxLines){
					break;
				}
				s.append(sc.nextLine() + "\n");
			}
			return s.toString();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(0);
			return null;
		}
	}
	
	public boolean containsDet(List<String> words){
		for(int i = 0; i < words.size(); i++){
			if(looseContains(determiners, words.get(i))){
				return true;
			}
		}
		return false;
	}
	
	public boolean containsDetWork(List<String> words){
		for(int i = 1; i < words.size(); i++){
			if(looseContains(workNouns, words.get(i))){
				if(looseContains(determiners, words.get(i-1))){
					return true;
				}
			}
		}
		return false;
	}
	
	public boolean startsWithDetWork(List<String> words){
		return words.size() >= 2 && looseContains(determiners, words.get(0)) && looseContains(workNouns, words.get(1));
	}
	
	public boolean startsWithLimitedDet(List<String> words){
		return words.size() >= 1 && (words.get(0).equalsIgnoreCase("this") || words.get(0).toLowerCase().equalsIgnoreCase("such"));
	}
	
	public boolean startsWith3rdPersonPronoun(List<String> words){
		return words.size() >= 1 && looseContains(thirdPersonPronouns, words.get(0));
	}
	
	public boolean startsWithConnector(List<String> words){
		return words.size() >= 1 && looseContains(connectors, words.get(0));
	}
	
	
	public boolean startsWithSectionHeader(List<String> words){
		return words != null && words.get(0).matches(HEADER_PATTERN);
//		return words != null ? words.stream().anyMatch(word -> word.matches(HEADER_PATTERN)) : false;
	}
	
	public boolean containsMainAuthor(List<String> words, String mainAuthor){
		String cleanAuthor = Normalizer.normalize(mainAuthor, Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "");
		for(int i = 0; i < words.size(); i++){
			String word = words.get(i);
//			if(word.matches("\\(?\\[?(" + mainAuthor  + "|" + cleanAuthor + ")'?s?,?\\)?]?")){
			if(word.contains(mainAuthor) || word.contains(cleanAuthor)){
				return true;
			}
		}
		return false;
	}
	
	public boolean containsAcronyms(List<String> sentence, Set<String> acronyms){
//		return acronyms.stream().anyMatch(acronym -> sentence.contains(acronym));
		for(String word : sentence){
			for(String acronym : acronyms){
				if(word.matches(".*" + acronym + ".*")){
					return true;
				}
			}
		}
		return false;
	}
	
	public boolean containsLexicalHooks(String sentence, Set<String> lexicalHooks){
//		return lexicalHooks.stream().anyMatch(hook -> sentence.contains(hook) || sentence.contains(hook.toLowerCase()));
		for(String hook : lexicalHooks){
			if(StringUtils.containsIgnoreCase(sentence, hook)){
				return true;
			}
		}
		return false;
	}
	
	private boolean looseContains(List<String> list, String str){
		
		if(str.endsWith("s")){
			str = str.substring(0, str.length() - 1);
		}
		return list.contains(str) || list.contains(str.toLowerCase());
	}
	
	public boolean containsExplicitCitation(List<String> words, String mainAuthor){
		int authorIndex = -1;
		
		for(int i = 0; i < words.size(); i++){
			String word = words.get(i);
			if(word.matches("\\(?" + mainAuthor + ",?\\)?")){
				authorIndex = i;
				break;
			}
		}
		
		if(authorIndex > -1){
			int start = Math.min(authorIndex+1, words.size()-1);
			int end = Math.min(authorIndex+6, words.size());
			List<String> vicinity = words.subList(start, end);
			String yearRegex = ".*\\d\\d\\d?\\d?.*";
			return vicinity.stream().anyMatch(word -> word.matches(yearRegex));
		}
		return false;
	}
	
	private static final Pattern NUM_OR_CHAR = Pattern.compile("\\d+|.");
	
	public TObjectDoubleHashMap<String> getNgramsTfIdf(int n, List<String> words, NgramIdf wordIdf){
		
		TObjectDoubleHashMap<String> unigrams = getNgrams(n, words, true);
		TObjectDoubleHashMap<String> tfIdf = new TObjectDoubleHashMap<String>();
		TObjectDoubleIterator<String> it = unigrams.iterator();
		while(it.hasNext()){
			it.advance();
			double tf = 1 + Math.log(it.value());
			String word = it.key();
			double idfCount = wordIdf.getIdf(n, word);
			if(idfCount > 0){ //Skip super rare words
				double idf = Math.log(1 + wordIdf.numDocuments/idfCount);
				tfIdf.adjustOrPutValue(word, tf*idf, tf*idf);
			}
		}
		return tfIdf;
	}
	
	public TObjectDoubleHashMap<String> getNgrams(int n, List<String> words, boolean skipStopwords){
		TObjectDoubleHashMap<String> ngramCounts = new TObjectDoubleHashMap<String>();
		
		for(int i = n - 1; i < words.size(); i++){
			List<String> ngramWords = words.subList(i - n + 1, i + 1);
			if(skipStopwords){
				if(ngramWords.stream().anyMatch(w -> stopwords.contains(w) || w.equals(NUMBER_TAG))){
					continue;
				}
			}
			if(NUM_OR_CHAR != null){
				if(ngramWords.stream().anyMatch(w -> NUM_OR_CHAR.matcher(w).matches())){
					continue;
				}
			}
			Stream<String> ngramWordsStream = ngramWords.stream();
			String ngram = ngramWordsStream.reduce((s1,s2) -> s1 + " " + s2).get();
			if(ngram.length() > 0){
				ngram = ngram.toLowerCase(); //TODO 
				ngramCounts.adjustOrPutValue(ngram, 1, 1);
			}
		}
		return ngramCounts;
	}
	
	public static String stem(String word){
		Stemmer s = new Stemmer();
		s.add(word);
		s.stem();
		return s.toString();
	}
}
