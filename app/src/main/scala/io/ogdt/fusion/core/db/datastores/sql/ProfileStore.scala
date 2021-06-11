package io.ogdt.fusion.core.db.datastores.sql

import io.ogdt.fusion.core.db.datastores.sql.OrganizationStore
import io.ogdt.fusion.core.db.wrappers.ignite.IgniteClientNodeWrapper
import io.ogdt.fusion.core.db.datastores.typed.SqlMutableStore
import io.ogdt.fusion.core.db.datastores.typed.sql.GetEntityFilters
import io.ogdt.fusion.core.db.models.sql.Profile
import org.apache.ignite.IgniteCache
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

import io.ogdt.fusion.core.db.models.sql.relations.{
    ProfileEmail,
    ProfileGroup,
    ProfilePermission
}

import java.sql.Timestamp
import java.util.UUID
import io.ogdt.fusion.core.db.common.Utils

import scala.jdk.CollectionConverters._
import org.apache.ignite.cache.CacheMode
import io.ogdt.fusion.core.db.datastores.typed.sql.SqlStoreQuery
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import org.apache.ignite.cache.CacheAtomicityMode

import io.ogdt.fusion.core.db.datastores.sql.exceptions.profiles.{
    ProfileNotPersistedException,
    ProfileQueryExecutionException,
    DuplicateProfileException,
    ProfileNotFoundException
}

import org.apache.ignite.cache.QueryEntity
import scala.util.Try
import io.ogdt.fusion.core.db.datastores.sql.generics.EmailStore
import io.ogdt.fusion.core.db.models.sql.OrganizationType
import io.ogdt.fusion.core.db.models.sql.generics.Language
import io.ogdt.fusion.core.db.datastores.sql.exceptions.NoEntryException

import io.ogdt.fusion.core.db.models.sql.relations.{
    ProfileEmail,
    ProfileGroup,
    ProfilePermission
}
import io.ogdt.fusion.core.db.datastores.sql.exceptions.users.UserNotFoundException
import java.time.Instant

class ProfileStore(implicit wrapper: IgniteClientNodeWrapper) extends SqlMutableStore[UUID, Profile] {

    override val schema: String = "FUSION"
    override val cache: String = s"SQL_${schema}_PROFILE"
    override protected var igniteCache: IgniteCache[UUID, Profile] = wrapper.cacheExists(cache) match {
        case true => wrapper.getCache[UUID, Profile](cache)
        case false => {
            wrapper.createCache[UUID, Profile](
                wrapper.makeCacheConfig[UUID, Profile]
                .setCacheMode(CacheMode.REPLICATED)
                .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
                .setDataRegionName("Fusion")
                .setQueryEntities(
                    List(
                        new QueryEntity(classOf[String], classOf[ProfileEmail])
                        .setTableName("PROFILE_EMAIL"),
                        new QueryEntity(classOf[String], classOf[ProfileGroup])
                        .setTableName("PROFILE_GROUP"),
                        new QueryEntity(classOf[String], classOf[ProfilePermission])
                        .setTableName("PROFILE_PERMISSION")
                    ).asJava
                )
                .setName(cache)
                .setSqlSchema(schema)
                .setIndexedTypes(classOf[UUID], classOf[Profile])
            )
        }
    }

    def makeProfile: Profile = {
        implicit val profileStore: ProfileStore = this
        new Profile
    }

    def makeProfilesQuery(queryFilters: ProfileStore.GetProfilesFilters): SqlStoreQuery = {
        var queryString: String = 
            "SELECT profile_id, profile_lastname, profile_firstname, profile_last_login, profile_is_active, profile_created_at, profile_updated_at, info_data, type_data " +
            "FROM " +
            "(SELECT PROFILE.id AS profile_id, lastname AS profile_lastname, firstname AS profile_firstname, last_login AS profile_last_login, is_active AS profile_is_active, PROFILE.created_at AS profile_created_at, PROFILE.updated_at AS profile_updated_at, " +
            "CONCAT_WS('||', USER.id, username, password, USER.created_at, USER.updated_at) AS info_data, 'USER' AS type_data " +
            s"FROM $schema.PROFILE AS PROFILE " +
            s"INNER JOIN $schema.USER as USER ON USER.id = PROFILE.user_id " +
            "UNION ALL " +
            "SELECT PROFILE.id AS profile_id, lastname AS profile_lastname, firstname AS profile_firstname, last_login AS profile_last_login, is_active AS profile_is_active, PROFILE.created_at AS profile_created_at, PROFILE.updated_at AS profile_updated_at,  " +
            "CONCAT_WS('||', ORG.id, ORG.label, ORG.queryable, ORG.created_at, ORG.updated_at) AS info_data, 'ORGANIZATION' AS type_data " +
            s"FROM $schema.PROFILE AS PROFILE " +
            s"INNER JOIN $schema.ORGANIZATION AS ORG ON PROFILE.organization_id = ORG.id " +
            "UNION ALL " +
            "SELECT PROFILE.id AS profile_id, lastname AS profile_lastname, firstname AS profile_firstname, last_login AS profile_last_login, is_active AS profile_is_active, PROFILE.created_at AS profile_created_at, PROFILE.updated_at AS profile_updated_at, " +
            "CONCAT_WS('||', ORG.id, TEXT.content, LANG.code, TEXT.language_id, LANG.label, TEXT.id, ORGTYPE.created_at, ORGTYPE.updated_at) AS info_data, 'ORGTYPE_LANG_VARIANT' AS type_data " +
            s"FROM $schema.PROFILE AS PROFILE " +
            s"LEFT OUTER JOIN $schema.ORGANIZATION AS ORG ON PROFILE.organization_id = ORG.id " +
            s"LEFT OUTER JOIN $schema.ORGANIZATIONTYPE AS ORGTYPE ON ORG.organizationtype_id = ORGTYPE.id " +
            s"LEFT OUTER JOIN $schema.TEXT AS TEXT ON TEXT.id = ORGTYPE.label_text_id " +
            s"LEFT OUTER JOIN $schema.LANGUAGE AS LANG ON TEXT.language_id = LANG.id " +
            "UNION ALL " +
            "SELECT PROFILE.id AS profile_id, lastname AS profile_lastname, firstname AS profile_firstname, last_login AS profile_last_login, is_active AS profile_is_active, PROFILE.created_at AS profile_created_at, PROFILE.updated_at AS profile_updated_at, " +
            "CONCAT_WS('||', EMAIL.id, EMAIL.address) AS info_data, 'EMAIL' AS type_data " +
            s"FROM $schema.PROFILE AS PROFILE " +
            s"LEFT OUTER JOIN $schema.PROFILE_EMAIL AS PROFILE_EMAIL ON PROFILE_EMAIL.profile_id = PROFILE.id " +
            s"LEFT OUTER JOIN $schema.EMAIL AS EMAIL ON PROFILE_EMAIL.email_id = EMAIL.id)"
        var queryArgs: ListBuffer[String] = ListBuffer()
        var whereStatements: ListBuffer[String] = ListBuffer()
        queryFilters.filters.foreach({ filter =>
            var innerWhereStatement: ListBuffer[String] = ListBuffer()
            // manage ids search
            if (filter.id.length > 0) {
                innerWhereStatement += s"profile_id in (${(for (i <- 1 to filter.id.length) yield "?").mkString(",")})"
                queryArgs ++= filter.id
            }
            // manage lastnames search
            if (filter.lastname.length > 0) {
                innerWhereStatement += s"profile_lastname in (${(for (i <- 1 to filter.lastname.length) yield "?").mkString(",")})"
                queryArgs ++= filter.lastname
            }
            // manage lastnames search
            if (filter.firstname.length > 0) {
                innerWhereStatement += s"profile_firstname in (${(for (i <- 1 to filter.firstname.length) yield "?").mkString(",")})"
                queryArgs ++= filter.firstname
            }
            // manage lastLogin date search
            filter.lastLogin match {
                case Some((test, time)) => {
                    innerWhereStatement += s"profile_last_login ${
                        test match {
                            case "eq" => "="
                            case "gt" => ">"
                            case "lt" => "<"
                            case "neq" => "<>"
                        }
                    } ?"
                    queryArgs += time.toString
                }
                case None => ()
            }
            // manage shared state search
            filter.isActive match {
                case Some(value) => {
                    innerWhereStatement += s"profile_is_active = ?"
                    queryArgs += value.toString
                }
                case None => ()
            }
            // manage metadate search
            filter.createdAt match {
                case Some((test, time)) => {
                    innerWhereStatement += s"profile_created_at ${
                        test match {
                            case "eq" => "="
                            case "gt" => ">"
                            case "lt" => "<"
                            case "neq" => "<>"
                        }
                    } ?"
                    queryArgs += time.toString
                }
                case None => ()
            }
            filter.updatedAt match {
                case Some((test, time)) => {
                    innerWhereStatement += s"profile_updated_at ${
                        test match {
                            case "eq" => "="
                            case "gt" => ">"
                            case "lt" => "<"
                            case "neq" => "<>"
                        }
                    } ?"
                    queryArgs += time.toString
                }
                case None => ()
            }
            whereStatements += innerWhereStatement.mkString(" AND ")
        })
        // compile whereStatements
        if (!whereStatements.isEmpty) {
            queryString += " WHERE " + whereStatements.reverse.mkString(" OR ")
        }
        // manage order
        if (!queryFilters.orderBy.isEmpty) {
            queryString += s" ORDER BY ${queryFilters.orderBy.map( o =>
                s"profile_${o._1} ${o._2 match {
                    case 1 => "ASC"
                    case -1 => "DESC"
                }}"
            ).mkString(", ")}"
        }
        println(queryArgs)
        makeQuery(queryString)
        .setParams(queryArgs.toList)
    }

    def getProfiles(queryFilters: ProfileStore.GetProfilesFilters)(implicit ec: ExecutionContext): Future[List[Profile]] = {
        executeQuery(makeProfilesQuery(queryFilters)).transformWith({
            case Success(profileResults) => {
                var profiles = profileResults.toList.groupBy(_(0)).map(entityReflection => {
                    (for (
                        profile <- Right(
                            makeProfile
                            .setId(entityReflection._2(0)(0).toString)
                            .setLastname(entityReflection._2(0)(1).toString)
                            .setFirstname(entityReflection._2(0)(2).toString)
                            .setLastLogin(entityReflection._2(0)(3) match {
                                case lastlogin: Timestamp => lastlogin
                                case _ => null
                            })
                            .setCreatedAt(entityReflection._2(0)(5) match {
                                case createdAt: Timestamp => createdAt
                                case _ => null
                            })
                            .setUpdatedAt(entityReflection._2(0)(6) match {
                                case updatedAt: Timestamp => updatedAt
                                case _ => null
                            })
                        ) flatMap { profile =>
                            entityReflection._2(0)(4) match {
                                case active: Boolean => {
                                    if (active) Right(profile.setActive)
                                    else Right(profile.setInactive)
                                }
                                case _ => Right(profile)
                            }
                        } flatMap { profile =>

                            val groupedRows = getRelationsGroupedRowsFrom(entityReflection._2, 7, 8)

                            groupedRows.get("USER") match {
                                case Some(userReflections) => {
                                    userReflections.foreach({ userReflection =>
                                        val userDef = userReflection(0)._2
                                        (for (
                                            user <- Right(new UserStore()
                                                .makeUser
                                                .setId(userDef(0))
                                                .setUsername(userDef(1))
                                                .setPasswordHash(userDef(2))
                                                .setCreatedAt(Timestamp.from(Instant.parse(userDef(3))) match {
                                                    case createdAt: Timestamp => createdAt
                                                    case _ => null
                                                })
                                                .setUpdatedAt(Timestamp.from(Instant.parse(userDef(4))) match {
                                                    case updatedAt: Timestamp => updatedAt
                                                    case _ => null
                                                })
                                            )
                                        ) yield profile.setRelatedUser(user))
                                    })
                                }
                                case None => Future.failed(new UserNotFoundException(s"Profile ${profile.id}:${profile.firstname}.${profile.lastname}"))
                            }

                            Right(profile)
                        }
                    ) yield profile)
                    .getOrElse(null)
                })
                Future.successful(profiles.toList)
            }
            case Failure(cause) => Future.failed(ProfileQueryExecutionException(cause))
        })
    }

    def getAllProfiles(implicit ec: ExecutionContext): Future[List[Profile]] = {
        getProfiles(
            ProfileStore.GetProfilesFilters().copy(
                orderBy = List(
                    ("id", 1)
                )
            )
        ).transformWith({
            case Success(profiles) =>
                profiles.length match {
                    case 0 => Future.failed(new NoEntryException("Profile store is empty"))
                    case _ => Future.successful(profiles)
                }
            case Failure(cause) => Future.failed(cause)
        })
    }

    

    def getProfileById(id: String)(implicit ec: ExecutionContext): Future[Profile] = {
        getProfiles(
            ProfileStore.GetProfilesFilters().copy(
                filters = List(
                    ProfileStore.GetProfilesFilter().copy(
                        id = List(id)
                    )
                )
            )
        ).transformWith({
            case Success(profiles) =>
                profiles.length match {
                    case 0 => Future.failed(new ProfileNotFoundException(s"Profile ${id} couldn't be found"))
                    case 1 => Future.successful(profiles(0))
                    case _ => Future.failed(new DuplicateProfileException)
                }
            case Failure(cause) => Future.failed(cause)
        })
    }

    def persistProfile(profile: Profile)(implicit ec: ExecutionContext): Future[Unit] = {
        (profile.relatedUser, profile.relatedOrganization) match {
            case (Some(relatedUser), Some(relatedOrganization)) => {
                val transaction = makeTransaction
                transaction match {
                    case Success(tx) => {
                        val emailRelationCache: IgniteCache[String, ProfileEmail] =
                            wrapper.getCache[String, ProfileEmail](cache)
                        val groupRelationCache: IgniteCache[String, ProfileGroup] =
                            wrapper.getCache[String, ProfileGroup](cache)
                        val permissionRelationCache: IgniteCache[String, ProfilePermission] =
                            wrapper.getCache[String, ProfilePermission](cache)
                        Future.sequence(
                            List(
                                // Save entity
                                Utils.igniteToScalaFuture(igniteCache.putAsync(
                                    profile.id, profile
                                )),
                                // Save emails
                                Utils.igniteToScalaFuture(emailRelationCache.putAllAsync(
                                    (profile.emails
                                    .filter(_._1 == true)
                                    .map({ email =>
                                        (
                                            profile.id +":"+ email._2.id,
                                            ProfileEmail(
                                                profile.id,
                                                email._2.id
                                            )
                                        )
                                    }) ++ List(
                                        (
                                            profile.id +":"+ profile.mainEmail.id,
                                            ProfileEmail(
                                                profile.id,
                                                profile.mainEmail.id,
                                                true
                                            )
                                        )
                                    )).toMap[String, ProfileEmail].asJava
                                )),
                                // Remove emails
                                Utils.igniteToScalaFuture(emailRelationCache.removeAllAsync(
                                    profile.emails
                                    .filter(_._1 == false)
                                    .map({ email => profile.id+":"+email._2.id }).toSet.asJava
                                )),
                                // Save groups
                                Utils.igniteToScalaFuture(groupRelationCache.putAllAsync(
                                    (profile.groups
                                    .filter(_._1 == true)
                                    .map({ group =>
                                        (
                                            profile.id+":"+group._2.id,
                                            ProfileGroup(
                                                profile.id,
                                                group._2.id
                                            )
                                        )
                                    })).toMap.asJava
                                )),
                                // Remove groups
                                Utils.igniteToScalaFuture(groupRelationCache.removeAllAsync(
                                    (profile.groups
                                    .filter(_._1 == false)
                                    .map({ group =>profile.id+":"+group._2.id })).toSet.asJava
                                )),
                                // Save permissions
                                Utils.igniteToScalaFuture(permissionRelationCache.putAllAsync(
                                    profile.permissions
                                    .filter(_._1 == true)
                                    .map({ permission => 
                                        (
                                            profile.id+":"+permission._2.id,
                                            ProfilePermission(
                                                profile.id,
                                                permission._2.id
                                            )
                                        )
                                    }).toMap.asJava
                                )),
                                // Remove permission
                                Utils.igniteToScalaFuture(permissionRelationCache.removeAllAsync(
                                    profile.permissions
                                    .filter(_._1 == false)
                                    .map({ permission => profile.id+":"+permission._2.id }).toSet.asJava
                                ))
                            )
                        ).transformWith({
                            case Success(value) => {
                                commitTransaction(transaction).transformWith({
                                    case Success(value) => Future.unit
                                    case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
                                })
                            }
                            case Failure(cause) => {
                                rollbackTransaction(transaction)
                                Future.failed(ProfileNotPersistedException(cause))
                            }
                        })
                    }
                    case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
                }
                Utils.igniteToScalaFuture(igniteCache.putAsync(
                    profile.id, profile
                )).transformWith({
                    case Success(value) => Future.unit
                    case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
                })
            }
            case (None, _) => Future.failed(ProfileNotPersistedException("relatedUser not found and can't be set to null"))
            case (_, None) => Future.failed(ProfileNotPersistedException("relatedOrganization not found and can't be set to null"))
        }
    }

    /** A result of bulkPersistProfiles method
      * 
      * @constructor create a new BulkPersistProfilesResult with a count of inserted Profiles and a list of errors
      * @param inserts a count of the effectively inserted Profiles
      * @param errors a list of errors catched from a profile insertion
      */
    case class BulkPersistProfilesResult(inserts: Int, errors: List[String])

    def bulkPersistProfiles(profiles: List[Profile])(implicit ec: ExecutionContext): Future[BulkPersistProfilesResult] = {
        Utils.igniteToScalaFuture(
            igniteCache.putAllAsync(
                (profiles.filter(profile => {
                    (profile.relatedUser, profile.relatedOrganization) match {
                        case (Some(relatedUser), Some(relatedOrganization)) => true
                        case (None, _) => false
                        case (_, None) => false
                    }
                }).map(_.id) zip profiles).toMap[UUID, Profile].asJava
            )
        ).transformWith({
            case Success(value) => {
                Future.sequence(
                    profiles.map(profile => Utils.igniteToScalaFuture(igniteCache.containsKeyAsync(profile.id)))
                ).map(lookup => (profiles zip lookup))
                .transformWith(lookup => {
                    Future.successful(BulkPersistProfilesResult(
                        lookup.get.filter(_._2 == true).length,
                        lookup.get.filter(_._2 == false).map("Insert profile "+_._1.toString+" failed")
                    ))
                })
            }
            case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
        })
    }

    def deleteProfile(profile: Profile)(implicit ec: ExecutionContext): Future[Unit] = {
        (profile.relatedUser, profile.relatedOrganization) match {
            case (Some(relatedUser), _) => Future.failed(new Error("Profile has still attached User"))
            case (_, Some(relatedOrganization)) => Future.failed(new Error("Profile has still attached Organization"))
            case (None, None) => {
                Utils.igniteToScalaFuture(igniteCache.removeAsync(profile.id))
                .transformWith({
                    case Success(value) => Future.unit
                    case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
                })
            }
        }
    }

    /** A result of bulkDeleteProfiles method
      * 
      * @constructor create a new BulkDeleteProfilesResult with a count of deleted Profiles and a list of errors
      * @param inserts a count of the effectively deleted Profiles
      * @param errors a list of errors catched from a profile deletion
      */
    case class BulkDeleteProfilesResult(inserts: Int, errors: List[String])

    def bulkDeleteProfiles(profiles: List[Profile])(implicit ec: ExecutionContext): Future[BulkDeleteProfilesResult] = {
        Utils.igniteToScalaFuture(igniteCache.removeAllAsync(
            (profiles.filter(profile => {
                (profile.relatedUser, profile.relatedOrganization) match {
                        case (Some(relatedUser), _) => false
                        case (_, Some(relatedOrganization)) => false
                        case (None, None) => true
                    }
            }).map(_.id).toSet.asJava))
        ).transformWith({
            case Success(value) => {
                Future.sequence(
                    profiles.map(profile => Utils.igniteToScalaFuture(igniteCache.containsKeyAsync(profile.id)))
                ).map(lookup => (profiles zip lookup))
                .transformWith(lookup => {
                    Future.successful(BulkDeleteProfilesResult(
                        lookup.get.filter(_._2 == false).length,
                        lookup.get.filter(_._2 == true).map("Failed to delete profile "+_._1.toString)
                    ))
                })
            }
            case Failure(cause) => Future.failed(ProfileNotPersistedException(cause))
        })
    }
}

object ProfileStore {
    case class GetProfilesFilter(
        id: List[String] = List(),
        lastname: List[String] = List(),
        firstname: List[String] = List(),
        lastLogin: Option[(String, Timestamp)] = None,
        isActive: Option[Boolean] = None,
        createdAt: Option[(String, Timestamp)] = None, // (date, (eq, lt, gt, ne))
        updatedAt: Option[(String, Timestamp)] = None, // (date, (eq, lt, gt, ne))
    )
    case class GetProfilesFilters(
        filters: List[GetProfilesFilter] = List(),
        orderBy: List[(String, Int)] = List() // (column, direction)
    ) extends GetEntityFilters
}
