package main;

import java.io.File;
import java.util.ArrayList;

import util.Environment;
import util.NgramIdf;
import citationContextData.Dataset;
import citationContextData.DatasetFactory;
import citationContextData.DatasetParams;
import citationContextData.Text;
import citationContextData.TextParams;
import citationContextData.TextWithNgrams;
import citationContextData.Xml;

public class CreateDatasetsSaveXml {
	public static void main(String[] args) {
		withNgrams();
	}
	
	private static void basic(){
		File resourcesDir = new File(Environment.resources());
		ArrayList<Dataset<Text>> datasets = DatasetFactory.fromHtmlDir(
				DatasetParams.enhanced(TextParams.basic(), 20, 1), 
				new File(resourcesDir, "teufel-citation-context-corpus"));
		for(Dataset<Text> dataset : datasets){
			Xml.writeToXml(dataset, new File(resourcesDir, "xml-datasets/" + dataset.datasetLabel + ".xml"));
		}
	}
	
	private static void withNgrams(){
		File resourcesDir = new File(Environment.resources());
		NgramIdf wordIdf = NgramIdf.fromXmlFile(new File(resourcesDir, "xml-datasets/ngram-frequencies.xml"));
		ArrayList<Dataset<TextWithNgrams>> datasets = DatasetFactory.fromHtmlDir(
				DatasetParams.enhanced(TextParams.withNgrams(wordIdf), 20, 1), 
				new File(resourcesDir, "teufel-citation-context-corpus"));
		for(Dataset<TextWithNgrams> dataset : datasets){
			Xml.writeToXml(dataset, new File(resourcesDir, "xml-datasets/" + dataset.datasetLabel + "-with-ngrams.xml"));
		}
	}
}
