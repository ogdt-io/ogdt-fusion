package io.ogdt.fusion.core.db.datastores.documents.aggregations

import io.ogdt.fusion.core.db.datastores.documents.aggregations.typed.{Pipeline, PipelineWrapper}

import reactivemongo.api.bson.collection.BSONCollection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactivemongo.api.bson.BSONDocument
import reactivemongo.api.bson.BSONValue
import reactivemongo.api.bson.BSONString
import reactivemongo.api.bson.BSONObjectID

object GetFileFromId extends PipelineWrapper {

    class GetFileFromIdPipeline(protected val _collection: BSONCollection) extends Pipeline {


        // DEBUG
        var logger: Logger = LoggerFactory.getLogger(getClass());
        // end-DEBUG

        private var _id = ""

        def setId(id: String): GetFileFromIdPipeline = {
            _id = id
            this
        }

        def get: List[_collection.AggregationFramework.PipelineOperator] = {

            import _collection.AggregationFramework.{
                Match,
                GraphLookup,
                Unwind,
                Sort,
                Project,
                Group,
                Descending,
                FirstField,
                PushField}

            List(
                Match(BSONDocument("_id" -> BSONObjectID.parse(_id).get)),
                GraphLookup(
                    from = _collection.name,
                    startWith = BSONString("$parent"),
                    connectFromField = "parent",
                    connectToField = "_id",
                    as = "ancestors",
                    depthField = Some("order")),
                Unwind(field = "ancestors"),
                Sort(Descending("ancestors.order")),
                Group(BSONString("$_id"))(
                    "name" -> FirstField("name"),
                    "type" -> FirstField("type"),
                    "parent" -> FirstField("parent"),
                    "chunkList" -> FirstField("chunkList"),
                    "metadata" -> FirstField("metadata"),
                    "versioned" -> FirstField("versioned"),
                    "acl" -> FirstField("acl"),
                    "owner" -> FirstField("owner"),
                    "ancestors" -> PushField("ancestors")
                ),
                Project(BSONDocument(
                    "name" -> 1,
                    "type" -> 1,
                    "parent" -> 1,
                    "chunkList" -> 1,
                    "metadata" -> 1,
                    "versioned" -> 1,
                    "acl" -> 1,
                    "owner" -> 1,
                    "path" -> BSONDocument(
                        "$concat" -> List[BSONValue](
                            BSONDocument(
                                "$reduce" -> BSONDocument(
                                    "input" -> BSONString("$ancestors"),
                                    "initialValue" -> "/",
                                    "in" -> BSONDocument(
                                        "$concat" -> List[BSONValue](
                                            BSONString("$$value"),
                                            BSONDocument(
                                                "$cond" -> Array[BSONValue](
                                                    BSONDocument(
                                                        "$eq" -> Array("$$value", "/")
                                                    ),
                                                    BSONString(""),
                                                    BSONString("/")
                                                )
                                            ),
                                            BSONString("$$this.name")
                                        )
                                    )
                                )
                            ),
                            BSONString("/"),
                            BSONString("$name")
                        )
                    )
                ))
            )
        }
    }

    override def pipeline(col: BSONCollection): GetFileFromIdPipeline = new GetFileFromIdPipeline(col)
}