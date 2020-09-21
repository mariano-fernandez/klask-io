package io.klask.repository.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.StringQuery;

import io.klask.config.Constants;
import io.klask.domain.File;
import io.klask.repository.search.mapper.ResultHighlightMapper;
import io.klask.repository.search.mapper.ResultTruncatedContentMapper;
import io.klask.web.rest.util.Queries;

/**
 * Created by jeremie on 27/06/16.
 */
@Configuration
public class CustomSearchRepositoryImpl implements CustomSearchRepository {

    private final Logger log = LoggerFactory.getLogger(CustomSearchRepositoryImpl.class);

    @Inject
    private ElasticsearchTemplate elasticsearchTemplate;

    /**
     * Return records for query, and highlight the fragment of content with the ResultHighlightMapper
     *
     * @param pageable
     * @param query
     * @param version
     * @param project
     * @return
     */
    @Override
    public Page<File> customSearchWithHighlightedSummary(Pageable pageable, String query, List<String> version, List<String> project, List<String> extension) {
        if (StringUtils.isEmpty(query)) {
            log.error("customSearchWithHighlightedSummary return null in case where query = {}", query);
            return null;
        }
        NativeSearchQueryBuilder nativeSearchQueryBuilder = Queries.constructSearchQueryBuilder(query);
        NativeSearchQuery nativeSearchQuery = nativeSearchQueryBuilder.build();

        SearchRequestBuilder searchRequestBuilder = constructRequestBuilder(nativeSearchQuery, pageable, version, project, extension);
        searchRequestBuilder.setFetchSource(null, "content");//dont get the content, we have the highlight !

        log.trace("==> Request  ES ==> \n{}", searchRequestBuilder);
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        log.trace("<== Response ES <== \n{}", response);

        ResultHighlightMapper mapper = new ResultHighlightMapper();
        return mapper.mapResults(response, File.class, nativeSearchQuery.getPageable());

    }


    /**
     * Return all records, and truncate the content with the ResultTruncatedContentMapper
     *
     * @param pageable
     * @param version
     * @param project
     * @return
     */
    @Override
    public Page<File> customfindAll(Pageable pageable, List<String> version, List<String> project, List<String> extension) {
        NativeSearchQueryBuilder nativeSearchQueryBuilder = Queries.constructSearchQueryBuilder("");
        NativeSearchQuery nativeSearchQuery = nativeSearchQueryBuilder.build();

        SearchRequestBuilder searchRequestBuilder = constructRequestBuilder(nativeSearchQuery, pageable, version, project, extension);

        SearchResponse response = searchRequestBuilder.execute().actionGet();

        ResultTruncatedContentMapper mapper = new ResultTruncatedContentMapper();
        return mapper.mapResults(response, File.class, nativeSearchQuery.getPageable());
    }

    @Override
    public File findOne(String id) {
        Criteria criteria = new Criteria("id");
        criteria.is(id);
        CriteriaQuery criteriaQuery = new CriteriaQuery(criteria);
        criteriaQuery.addCriteria(criteria);

        StringQuery stringQuery = new StringQuery(QueryBuilders.termQuery("id", id).toString());

        stringQuery.addIndices(Constants.ALIAS);
//        ElasticsearchPersistentEntity persistentEntity = converter.getMappingContext().getPersistentEntity(File.class);
//
//        GetResponse response = elasticsearchTemplate.getClient()
//            .prepareGet(Constants.ALIAS, "*", id).execute()
//            .actionGet();
//        elasticsearchTemplate.map
//        T entity = mapper.mapResult(response, clazz);
        return elasticsearchTemplate.queryForObject(stringQuery,File.class);
    }

    @Override
    public Map<String, Long> aggregateByRawField(String field, String filtre) {

        TermsBuilder aggregation = AggregationBuilders.terms("top_" + field)
            .field(field + ".raw")
            .size(0)// le résultat n'est pas complet si on ne précise pas la taille, 0 : infini
            // (voir : https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-terms-aggregation.html#_size)
            .order(Terms.Order.aggregation("_count", false));

        SearchResponse response = createResponseForAggregate(filtre, aggregation);

        Map<String, Aggregation> results = response.getAggregations().asMap();
        Aggregation topFieldTerms = results.get("top_" + field);
        if (!(topFieldTerms instanceof StringTerms)) {
            return new LinkedHashMap<>();
        }
        StringTerms topField = (StringTerms) topFieldTerms;

        //sur l'ensemble des buckets, triés par ordre décroissant sur le nombre de documents
        // on retourne une Map (LinkedHashMap) pour conserver l'ordre avec la clé, le nom du champ (exemple version), et la valeur, le nombre de docs
        //exemple :
        // "trunk" -> 34012
        // "branche_1" -> 35800
        return topField.getBuckets()
            .stream()
            .sorted(Comparator.comparing(Terms.Bucket::getDocCount).reversed())
            .collect(
                Collectors.toMap(bucket -> bucket.getKeyAsString(), bucket -> bucket.getDocCount(), (v1, v2) -> v1, LinkedHashMap::new
                ));

    }


    /**
     * construct a SearchRequestBuilder from scratch
     *
     * @param pageable
     * @param nativeSearchQuery
     * @param version
     * @param project
     * @return
     */
    private SearchRequestBuilder constructRequestBuilder(NativeSearchQuery nativeSearchQuery, Pageable pageable, List<String> version, List<String> project, List<String> extension) {

        BoolQueryBuilder ensembleVersion = QueryBuilders.boolQuery();
        BoolQueryBuilder ensembleProjet = QueryBuilders.boolQuery();
        BoolQueryBuilder ensembleExtension = QueryBuilders.boolQuery();
        BoolQueryBuilder filter = QueryBuilders.boolQuery();

        if (version != null && !version.isEmpty()) {
            ensembleVersion = ensembleVersion.should(QueryBuilders.termsQuery("version.raw", version));
            filter = filter.must(ensembleVersion);
        }
        if (project != null && !project.isEmpty()) {
            ensembleProjet = ensembleProjet.should(QueryBuilders.termsQuery("project.raw", project));
            filter = filter.must(ensembleProjet);
        }
        if (extension != null && !extension.isEmpty()) {
            ensembleExtension = ensembleExtension.should(QueryBuilders.termsQuery("extension.raw", extension));
            filter = filter.must(ensembleExtension);
        }

        SearchRequestBuilder searchRequestBuilder = this.templateResponse()
            .setQuery(nativeSearchQuery.getQuery())
            .setHighlighterEncoder("html")//permet d'échapper tous les caractères html pour une sortie correcte sur le frontend
            .setHighlighterFragmentSize(100)
            .setHighlighterNumOfFragments(3)
            .setHighlighterPreTags("<mark>")
            .setHighlighterPostTags("</mark>")
            .addHighlightedField("content")//on souhaite la coloration Highligh sur le contenu et le path à l'affichage
            .addHighlightedField("path")
            .setHighlighterBoundaryChars(new char[]{'\n'})
            .setHighlighterBoundaryMaxScan(200)
            .setHighlighterType("fvh")
            .setTrackScores(true)
            .setPostFilter(filter);

        //searchRequestBuilder.addSort(nativeSearchQuery.getElasticsearchSorts().stream().findFirst().get());

        //add the sort order to searchRequestBuilder
        addPagingAndSortingToSearchRequest(pageable, searchRequestBuilder);


        return searchRequestBuilder;
    }

    /**
     * add the sort order to the request searchRequestBuilder
     * if the frontend send sort with "path : desc". It should be converted to "path.raw" : {"order" : "desc" }
     * https://www.elastic.co/guide/en/elasticsearch/guide/current/multi-fields.html#multi-fields
     *
     * @param pageable
     * @param searchRequestBuilder
     */
    private void addPagingAndSortingToSearchRequest(Pageable pageable, SearchRequestBuilder searchRequestBuilder) {
        //par défaut, renvoi la première page trié sur le _score ou le _doc, si rien n'est spécifié
        //effectue le tri
        if (pageable != null) {

            searchRequestBuilder
                .setFrom(pageable.getOffset())
                .setSize(pageable.getPageSize());

            if (pageable.getSort() != null) {
                pageable.getSort().forEach(
                    order -> {
                        SortBuilder sb;
                        //cas particulier si on a l'id en filtre, il doit être unmappedType pour éviter l'erreur du
                        // "all shards failed : No mapping found for [id] in order to sort on"
                        if("id".equals(order.getProperty())) {
                            sb = new FieldSortBuilder("id").unmappedType("string").order(SortOrder.valueOf(order.getDirection().name()));
                        } else {
                            sb = new FieldSortBuilder(Constants.ORDER_FIELD_MAPPING.get(order.getProperty()))
                                .order(SortOrder.valueOf(order.getDirection().name()));
                        }
                            searchRequestBuilder.addSort(sb);
                    }
                );
            }
        }
    }


    /**
     * create a SearchResponse with the main search query (from the FileResource /api/_search/files)
     *
     * @param query
     * @param aggregation
     * @return
     */
    private SearchResponse createResponseForAggregate(String query, TermsBuilder aggregation) {
        SearchResponse response;
        if (StringUtils.isNotEmpty(query)) {
            response = this.templateResponse()
                .setSize(0)// pour l'aggrégation, on ne désire pas retourner les résultats contenu dans le hits, mais seulement l'aggrégation !
                //attention, ce n'est pas le même size(0) qui se trouve dans l'aggrégation, qui permet lui de retourner l'ensemble des buckets sur tous les shards

                //ici nous utilisons la même querybuilder que dans la recherche principale pour obtenir justement
                //le même filtrage sur les versions courantes
                .setQuery(Queries.constructQuery(query))
                .addAggregation(aggregation)
                .execute().actionGet();
        } else {
            response = this.templateResponse()
                .setSize(0)
                .addAggregation(aggregation)
                .execute().actionGet();
        }
        return response;
    }


    private SearchRequestBuilder templateResponse() {
        return elasticsearchTemplate.getClient()
            .prepareSearch(Constants.ALIAS)
            .setTypes(Constants.TYPE_NAME)
            //.setIndices(Constants.ALIAS)//using alias to query
            //.setTypes(RepositoryType.getAllTypes())
            ;//SVN, GIT, FILE_SYSTEM
    }
}
