import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations.SentimentAnnotatedTree;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.util.CoreMap;

public class Extract {

	final String stopwordsFile = "stopwords.txt";
	final String requirementWordsFile = "requirementWords.txt";
	double overallSentiment = 0;
	int sentimentCount = 0;

	List<String> stopwords;
	List<String> featureList;

	public Extract() {

		try {
			stopwords = LoadStopwords();
			featureList = LoadrequirementWords();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static List<Pattern> ExtractPrimaryPatterns(Collection<TypedDependency> tdl) {
		List<Pattern> primary = new ArrayList<Pattern>();

		for (TypedDependency td : tdl) {
			Pattern pattern = TryExtractPattern(td);
			if (pattern != null) {
				primary.add(pattern);
			}
		}

		return primary;
	}

	private static Pattern TryExtractPattern(TypedDependency dependency) {
		String rel = dependency.reln().toString();
		String gov = dependency.gov().value();
		String govTag = dependency.gov().tag();
		String dep = dependency.dep().value();
		String depTag = dependency.dep().tag();

		Pattern.Relation relation = Pattern.asRelation(rel);
		if (relation != null) {
			Pattern pattern = new Pattern(gov, govTag, dep, depTag, relation);
			if (pattern.isPrimaryPattern()) {

				return pattern;
			}
		}

		return null;
	}

	private static List<Pattern> ExtractCombinedPatterns(List<Pattern> combined, List<Pattern> primary) {
		List<Pattern> results = new ArrayList<Pattern>();

		for (Pattern pattern : combined) {
			Pattern aspect = pattern.TryCombine(primary);
			if (aspect != null) {
				results.add(aspect);
			}
		}

		return results;
	}

	@SuppressWarnings("deprecation")
	private HashSet<Pattern> ExtractSentencePatterns(CoreMap sentence) {
		SemanticGraph semanticGraph = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);

		List<Pattern> primary = ExtractPrimaryPatterns(semanticGraph.typedDependencies());

		List<Pattern> combined;
		combined = ExtractCombinedPatterns(primary, primary);
		combined.addAll(ExtractCombinedPatterns(combined, primary));
		combined.addAll(ExtractCombinedPatterns(combined, primary));

		return PruneCombinedPatterns(combined);
	}

	private HashSet<Pattern> PruneCombinedPatterns(List<Pattern> combined) {
		List<Pattern> remove = new ArrayList<Pattern>();

		HashSet<Pattern> patterns = new HashSet<Pattern>(combined);
		for (Pattern pattern : patterns) {
			if (patterns.contains(pattern.mother) && pattern.mother.relation != Pattern.Relation.conj_and
					&& pattern.father.relation != Pattern.Relation.conj_and) {
				remove.add(pattern.mother);
				remove.add(pattern.father);
			}

			//remove patterns containing stopwords
			if (stopwords.contains(pattern.head) || stopwords.contains(pattern.modifier)) {
				remove.add(pattern);
			}

			//remove pattern if not relevant
			if (featureList.contains(pattern.head.toLowerCase()) || featureList.contains(pattern.modifier.toLowerCase())) {

			}
			else {
				remove.add(pattern);
			}
		}
		patterns.removeAll(remove);

		return patterns;
	}

	private List<String> LoadStopwords() throws IOException {
		List<String> words = new ArrayList<String>();

		BufferedReader br = new BufferedReader(new FileReader(stopwordsFile));
		String line;
		while ((line = br.readLine()) != null) {
			words.add(line);
		}
		br.close();

		return words;
	}

	private List<String> LoadrequirementWords() throws IOException {
		List<String> words = new ArrayList<String>();
		BufferedReader br = new BufferedReader(new FileReader(requirementWordsFile));
		String line;
		while ((line = br.readLine()) != null) {
			words.add(line);
		}
		br.close();

		return words;
	}
	
	public double getOverallSentiment() {
		return overallSentiment / sentimentCount;
	}
	
	public List<Pattern> run(String text) {
		List<Pattern> patterns = new ArrayList<Pattern>();
		
		Properties props = new Properties();
		props.setProperty("annotators", "tokenize, ssplit, pos, parse, sentiment");
		StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
		Annotation annotation = pipeline.process(text.toLowerCase());
		for (CoreMap sentence : annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
			patterns.addAll(ExtractSentencePatterns(sentence));
			Tree tree = sentence.get(SentimentAnnotatedTree.class);
			double sentiment = RNNCoreAnnotations.getPredictedClass(tree);
			overallSentiment += (sentiment + 1);
			//System.out.println(sentence + " " + sentiment);
			sentimentCount++;
		}
		for (Pattern pattern : patterns) {
			Annotation PatternAnnotation = pipeline.process(pattern.toAspect());
			for (CoreMap sentence : PatternAnnotation.get(CoreAnnotations.SentencesAnnotation.class)) {
				Tree tree = sentence.get(SentimentAnnotatedTree.class);
				double sentiment = RNNCoreAnnotations.getPredictedClass(tree);
				//System.out.println(sentence + " " + sentiment);
				pattern.setSentiment(sentiment);
				//System.out.println(sentiment);
				//  for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
				//      String lemma = token.get(CoreAnnotations.LemmaAnnotation.class);
				//System.out.println(lemma);
				// }
			}
		}

		return patterns;
	}
}
