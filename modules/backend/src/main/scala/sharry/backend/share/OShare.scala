package sharry.backend.share

import fs2.Stream
import bitpeace.FileMeta
import cats.implicits._
import cats.effect._
import org.log4s.getLogger

import sharry.common._
import sharry.common.syntax.all._
import sharry.store.Store
import sharry.store.records.RShare
import bitpeace.MimetypeHint
import sharry.store.records.RShareFile
import bitpeace.FileChunk
import cats.data.OptionT
import sharry.backend.PasswordCrypt
import sharry.store.records.RAlias
import sharry.store.AddResult
import sharry.store.records.RPublishShare
import bitpeace.RangeDef
import bitpeace.Outcome

trait OShare[F[_]] {

  /** Create a new share given 'data'.
    *
    * If 'data' contains files, they are added to the share. Errors
    * when adding the files are skipped, meaning that this operation
    * succeeds even if no file could be added.
    *
    * If the account-id contains an alias, it is used to supply most
    * meta data instead of 'data'. However, the alias is not checked
    * for validity, as it is assumed to be part of the authentication
    * process.
    */
  def create(data: ShareData[F], accId: AccountId): F[UploadResult[Ident]]

  /** Adds more files to an existing share.
    */
  def addFile(
      shareId: Ident,
      accId: AccountId,
      files: Stream[F, File[F]]
  ): OptionT[F, UploadResult[Ident]]

  /** Create a new file without any content to a given share.
    *
    * This is used with 'addFileData' when uploading a file in chunks.
    *
    * It is checked if the account is allowed to create a file. That
    * is, the 'share' id must belong to an existing share owned by the
    * given account. If the 'accId' contains an alias, the existing
    * share must be linked to that alias.
    *
    * The id of the new file is returned. If there is no share with
    * the given id, 'None' is returned.
    */
  def createEmptyFile(
      share: Ident,
      accId: AccountId,
      info: FileInfo
  ): OptionT[F, UploadResult[Ident]]

  /** Add a new chunk of bytes to an existing file.
    *
    * When files are uploaded in chunks, or an upload is resumed, file
    * data can be uploaded starting from an offset. This offset is
    * usually retrieved first using 'getFileData'.
    *
    * It is checked if the account is allowed to create a file. That
    * is, the 'fileId' id must belong to an existing file owned by the
    * given account. If the 'accId' contains an alias, the
    * corresponding share must be linked to that alias.
    */
  def addFileData(
      shareId: Ident,
      fileId: Ident,
      accId: AccountId,
      length: Option[ByteSize],
      offset: ByteSize,
      data: Stream[F, Byte]
  ): OptionT[F, UploadResult[ByteSize]]

  /** Return information about an existing file.
    *
    * It is checked if the file is owned by the given account. That
    * is, the 'fileId' id must belong to an existing file owned by the
    * given account. If the 'accId' contains an alias, the
    * corresponding share must be linked to that alias.
    */
  def getFileData(fileId: Ident, accId: AccountId): OptionT[F, FileData]

  /** Searches your shares.
    *
    * The query is applied to the name, id and alias name.
    */
  def findShares(q: String, accId: AccountId): Stream[F, ShareItem]

  /** Get all details about a share.
    */
  def shareDetails(
      id: ShareId,
      pass: Option[Password]
  ): OptionT[F, ShareResult[ShareDetail]]

  /** Publishes a share.
    *
    * If 'reuseId' is true and this share was previously published,
    * the id will be reused so the link doesn't change. Otherwise a
    * new id will be generated.
    *
    * No changes are applied if this share is already published.
    */
  def publish(share: Ident, accId: AccountId, reuseId: Boolean): OptionT[F, Unit]

  def unpublish(share: Ident, accId: AccountId): OptionT[F, Unit]

  def loadFile(
      id: ShareId,
      file: Ident,
      pass: Option[Password],
      range: RangeDef
  ): OptionT[F, FileRange[F]]

  def deleteFile(accId: AccountId, file: Ident): OptionT[F, Unit]

  def deleteShare(accId: AccountId, share: Ident): OptionT[F, Unit]

  def setDescription(accId: AccountId, share: Ident, value: String): OptionT[F, Unit]

  def setName(accId: AccountId, share: Ident, value: Option[String]): OptionT[F, Unit]

  def setValidity(accId: AccountId, share: Ident, value: Duration): OptionT[F, Unit]

  def setMaxViews(accId: AccountId, share: Ident, value: Int): OptionT[F, Unit]

  def setPassword(
      accId: AccountId,
      share: Ident,
      value: Option[Password]
  ): OptionT[F, Unit]

  /** Deletes all shares (with all its data) that have been published
    * but are expired now.
    */
  def cleanupExpired(invalidAge: Duration): F[Int]

  /** Deletes files that have no reference to a share. This should
    * actually never happen, but it might be possible due to bugs or
    * manually modifying the database.
    */
  def deleteOrphanedFiles: F[Int]
}

object OShare {
  private[this] val logger = getLogger

  def apply[F[_]: ConcurrentEffect](
      store: Store[F],
      cfg: ShareConfig
  ): Resource[F, OShare[F]] =
    Resource.pure[F, OShare[F]](new OShare[F] {

      def create(data: ShareData[F], accId: AccountId): F[UploadResult[Ident]] = {
        val createShare = for {
          share <- createShareRecord(
            store,
            accId.id,
            accId.alias,
            data.copy(password = data.password.map(PasswordCrypt.crypt))
          )
          valid = UploadResult(share).checkValidity(cfg.maxValidity)(_.validity)
          _ <- valid.mapF(r => store.transact(RShare.insert(r)))
          _ <- logger.fdebug(s"Result creating share for '${accId.id.id}': $valid")
        } yield valid.map(_.id)

        val storeFiles = (id: Ident) =>
          data.files
            .evalMap(createFile(store, id, cfg.chunkSize, cfg.maxSize))
            .evalMap(ur =>
              ur.toOption match {
                case Some(t) =>
                  logger.fdebug(s"Successfully stored file ${t._2.filename}")
                case None =>
                  logger.fwarn(s"Unable to store file: $ur")
              }
            )
            .compile
            .drain

        val advertisedSize: F[ByteSize] =
          data.files
            .map(_.length.getOrElse(0L))
            .fold(0L)(_ + _)
            .compile
            .lastOrError
            .map(ByteSize.apply)

        for {
          shareId <- createShare
          advSize <- advertisedSize
          _       <- shareId.checkSize(cfg.maxSize)(_ => advSize).mapF(storeFiles)
        } yield shareId
      }

      def addFile(
          shareId: Ident,
          accId: AccountId,
          files: Stream[F, File[F]]
      ): OptionT[F, UploadResult[Ident]] = {
        val storeFiles =
          files
            .evalMap(createFile(store, shareId, cfg.chunkSize, cfg.maxSize))
            .evalMap(ur =>
              ur.toOption match {
                case Some(t) =>
                  logger.fdebug(s"Successfully stored file ${t._2.filename}")
                case None =>
                  logger.fwarn(s"Unable to store file: $ur")
              }
            )
            .compile
            .drain

        for {
          _ <- OptionT(store.transact(Queries.checkShare(shareId, accId)))
          _ <- OptionT.liftF(storeFiles)
        } yield UploadResult(shareId)
      }

      def createEmptyFile(
          share: Ident,
          accId: AccountId,
          info: FileInfo
      ): OptionT[F, UploadResult[Ident]] = {
        val insert = for {
          fid <- Ident.randomId[F]
          sid <- Ident.randomId[F]
          now <- Timestamp.current[F]
          rest   = if (info.length % cfg.chunkSize.bytes == 0) 0 else 1
          chunks = info.length / cfg.chunkSize.bytes + rest
          fm = FileMeta(
            fid.id,
            now.value,
            info.mime,
            info.length,
            "",
            chunks.toInt,
            cfg.chunkSize.bytes.toInt
          )
          rf = RShareFile(sid, share, fid, info.name, now, ByteSize.zero)
          _ <- store.bitpeace.saveFileMeta(fm).compile.drain
          _ <- store.transact(RShareFile.insert(rf))
          _ <- logger.fdebug(s"Created empty file: ${sid.id}")
        } yield rf.id

        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          res <- OptionT.liftF(
            checkShareSize(store, cfg.maxSize, share, ByteSize(info.length))
          )
          r <- OptionT.liftF(res.mapF(_ => insert))
        } yield r
      }

      def addFileData(
          shareId: Ident,
          fileId: Ident,
          accId: AccountId,
          length: Option[ByteSize],
          offset: ByteSize,
          data: Stream[F, Byte]
      ): OptionT[F, UploadResult[ByteSize]] = {
        val startChunk = (offset.bytes / cfg.chunkSize.bytes).toInt
        val reqLen     = length.getOrElse(ByteSize.zero)

        def storeChunk(
            fileMetaId: Ident,
            length: ByteSize,
            mimeHint: MimetypeHint,
            sizeLeft: ByteSize
        ) =
          logger.fdebug(s"Start storing request of size ${reqLen.toHuman}") *>
            data
              .take(sizeLeft.bytes + 1)
              .chunkN(cfg.chunkSize.bytes.toInt)
              .zipWithIndex
              .map(tc => FileChunk(fileMetaId.id, tc._2 + startChunk, tc._1.toByteVector))
              .flatMap(chunk =>
                Stream.eval(
                  logger.ftrace(
                    s"Storing chunk ${chunk.chunkNr} of size ${chunk.chunkData.size}"
                  )
                ) >>
                  store.bitpeace
                    .addChunkByLength(
                      chunk,
                      cfg.chunkSize.bytes.toInt,
                      length.bytes,
                      mimeHint
                    )
                    .evalMap({
                      case Outcome.Created(_) =>
                        store.transact(
                          RShareFile.addRealSize(fileId, ByteSize(chunk.chunkData.size))
                        )
                      case Outcome.Unmodified(_) =>
                        0.pure[F]
                    })
                    .map(_ => chunk.chunkData.size)
              )
              .fold1(_ + _)
              .compile
              .last
              .map(_.getOrElse(0L))
              .flatMap { bytesSaved =>
                val len = offset + ByteSize(bytesSaved)
                store.transact(RShareFile.setRealSize(fileId, len)).map(_ => len)
              }

        val deleteFile = store
          .transact(RShareFile.delete(fileId))
          .flatTap(_ =>
            logger.fwarn("Deleting file due to max-size when uploading chunk!")
          )
          .map(_ => UploadResult.sizeExceeded[Long](cfg.maxSize))
          .map(_.map(ByteSize.apply))

        for {
          _ <- OptionT(store.transact(Queries.checkFile(fileId, accId)))
          res <- OptionT.liftF(
            checkShareSize(store, cfg.maxSize, shareId, reqLen)
          )
          desc <- OptionT(store.transact(Queries.fileDesc(fileId)))
          next <- OptionT.liftF(
            res.mapF(rem =>
              storeChunk(
                desc.metaId,
                desc.length,
                MimetypeHint(desc.name, desc.mime.some),
                rem
              )
            )
          )
          // check again against db state, because of parallel uploads
          currentSize2 <- OptionT.liftF(store.transact(Queries.shareSize(shareId)))
          ur <- OptionT.liftF(next.flatMapF { _ =>
            if (currentSize2 >= cfg.maxSize) deleteFile
            else next.pure[F]
          })

        } yield next
      }

      def getFileData(fileId: Ident, accId: AccountId): OptionT[F, FileData] =
        OptionT(store.transact(Queries.fileData(fileId)))

      def findShares(q: String, accId: AccountId): Stream[F, ShareItem] =
        store.transact(Queries.findShares(q, accId))

      def shareDetails(
          shareId: ShareId,
          pass: Option[Password]
      ): OptionT[F, ShareResult[ShareDetail]] =
        for {
          sd <- OptionT(store.transact(Queries.shareDetail(shareId).value))
          res = checkPassword(shareId, pass, sd.share.password)
          _ <- OptionT.liftF(
            res.mapF(_ => store.transact(Queries.countPublishAccess(shareId)))
          )
        } yield res.map(_ => sd)

      def publish(share: Ident, accId: AccountId, reuseId: Boolean): OptionT[F, Unit] = {
        val insert = RPublishShare.initialInsert(share).map(_ => 1)
        val exists = RPublishShare.existsByShare(share)
        val add: F[Int] = store.add(insert, exists).flatMap {
          case AddResult.Success =>
            1.pure[F]
          case AddResult.EntityExists(m) =>
            store.transact(RPublishShare.update(share, true, reuseId))
          case AddResult.Failure(ex) =>
            Effect[F].raiseError(ex)
        }

        for {
          _   <- OptionT(store.transact(Queries.checkShare(share, accId)))
          res <- OptionT.liftF(add)
        } yield ()
      }

      def unpublish(share: Ident, accId: AccountId): OptionT[F, Unit] = {
        val remove = store.transact(RPublishShare.update(share, false, true))
        for {
          _   <- OptionT(store.transact(Queries.checkShare(share, accId)))
          res <- OptionT.liftF(remove)
        } yield ()
      }

      def loadFile(
          shareId: ShareId,
          file: Ident,
          pass: Option[Password],
          range: RangeDef
      ): OptionT[F, FileRange[F]] = {
        val checkQuery = shareId.fold(
          pub => Queries.checkFilePublish(pub.id, file),
          priv =>
            Queries
              .checkFile(file, priv.account)
              .map(opt => opt.map(_ => (None: Option[Password])))
        )

        for {
          _    <- OptionT(store.transact(checkQuery))
          file <- ByteResult.load(store)(file, range)
        } yield file
      }

      def deleteFile(accId: AccountId, file: Ident): OptionT[F, Unit] =
        for {
          _  <- OptionT(store.transact(Queries.checkFile(file, accId)))
          fd <- OptionT(store.transact(Queries.fileDesc(file)))
          _  <- OptionT.liftF(store.transact(RShareFile.delete(file)))
          _ <- OptionT.liftF(
            ConcurrentEffect[F].start(
              Queries.deleteFile(store)(fd.metaId) *> logger.fdebug(
                s"File deleted: ${file.id}"
              )
            )
          )
        } yield ()

      def deleteShare(accId: AccountId, share: Ident): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          _ <- OptionT.liftF(Queries.deleteShare(share, true)(store))
        } yield ()

      def setDescription(
          accId: AccountId,
          share: Ident,
          value: String
      ): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          _ <- OptionT.liftF(store.transact(Queries.setDescription(share, value)))
        } yield ()

      def setName(
          accId: AccountId,
          share: Ident,
          value: Option[String]
      ): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          _ <- OptionT.liftF(store.transact(Queries.setName(share, value)))
        } yield ()

      def setValidity(accId: AccountId, share: Ident, value: Duration): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          _ <- OptionT.liftF(store.transact(Queries.setValidity(share, value)))
        } yield ()

      def setMaxViews(accId: AccountId, share: Ident, value: Int): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          _ <- OptionT.liftF(store.transact(Queries.setMaxViews(share, value)))
        } yield ()

      def setPassword(
          accId: AccountId,
          share: Ident,
          value: Option[Password]
      ): OptionT[F, Unit] =
        for {
          _ <- OptionT(store.transact(Queries.checkShare(share, accId)))
          pw = value.map(PasswordCrypt.crypt)
          _ <- OptionT.liftF(store.transact(Queries.setPassword(share, pw)))
        } yield ()

      def cleanupExpired(invalidAge: Duration): F[Int] =
        for {
          now <- Timestamp.current[F]
          point = now.minus(invalidAge)
          n <-
            store
              .transact(Queries.findExpired(point))
              .evalMap(id =>
                logger
                  .fdebug(s"Delete expired share: ${id.id}") *> Queries
                  .deleteShare(id, false)(
                    store
                  )
              )
              .compile
              .fold(0)((n, _) => n + 1)
        } yield n

      def deleteOrphanedFiles: F[Int] =
        for {
          n <-
            store
              .transact(Queries.findOrphanedFiles)
              .evalMap(id =>
                logger.fdebug(s"Delete orphaned file '${id.id}'") *> Queries.deleteFile(
                  store
                )(id)
              )
              .compile
              .fold(0)((n, _) => n + 1)
        } yield n
    })

// --- utilities

  private def checkPassword(
      shareId: ShareId,
      given: Option[Password],
      sharePw: Option[Password]
  ): ShareResult[Unit] =
    shareId match {
      case ShareId.PrivateId(_, _) =>
        ShareResult.Success(())
      case ShareId.PublicId(_) =>
        (given, sharePw) match {
          case (Some(plain), Some(pw)) =>
            if (PasswordCrypt.check(plain, pw)) ShareResult.Success(())
            else ShareResult.PasswordMismatch

          case (None, Some(pw)) =>
            ShareResult.PasswordMissing

          case _ =>
            ShareResult.Success(())
        }
    }

  private def checkShareSize[F[_]: Sync](
      store: Store[F],
      maxSize: ByteSize,
      shareId: Ident,
      fileSize: ByteSize
  ) =
    for {
      currentSize <- store.transact(Queries.shareSize(shareId))
      sizeLeft = maxSize - currentSize
      result =
        if (fileSize > sizeLeft) UploadResult.sizeExceeded(maxSize)
        else UploadResult(sizeLeft)
    } yield result

  def createFile[F[_]: Sync](
      store: Store[F],
      shareId: Ident,
      chunkSz: ByteSize,
      maxSize: ByteSize
  )(
      file: File[F]
  ): F[UploadResult[(FileMeta, RShareFile)]] = {

    def deleteFileMeta(fm: FileMeta) =
      for {
        _ <- logger.fdebug(s"Deleting too large (${ByteSize(fm.length)}) file ${fm.id}")
        _ <- store.bitpeace.delete(fm.id).compile.lastOrError
      } yield UploadResult.sizeExceeded[FileMeta](maxSize)

    def insertFileData(now: Timestamp) =
      for {
        // first check advertised length against max-size
        result <-
          checkShareSize[F](store, maxSize, shareId, ByteSize(file.length.getOrElse(0L)))

        // store file, at most max-size +1 bytes
        urfm <- result.mapF(sizeLeft =>
          store.bitpeace
            .saveNew(
              file.data.take(sizeLeft.bytes + 1L),
              chunkSz.bytes.toInt,
              MimetypeHint(file.name, file.advertisedMime.map(_.asString)),
              None,
              now.value
            )
            .compile
            .lastOrError
        )

        // check again against db state, because of parallel uploads
        currentSize2 <- store.transact(Queries.shareSize(shareId))
        ur <- urfm.flatMapF { fm =>
          if (currentSize2 >= maxSize) deleteFileMeta(fm)
          else urfm.pure[F]
        }
      } yield ur

    def saveShareFile(fm: FileMeta, now: Timestamp) =
      for {
        sfid <- Ident.randomId[F]
        sf = RShareFile(
          sfid,
          shareId,
          Ident.unsafe(fm.id),
          file.name,
          now,
          ByteSize(fm.length)
        )
        _ <- store.transact(RShareFile.insert(sf))
      } yield UploadResult((fm, sf))

    for {
      now  <- Timestamp.current[F]
      urfm <- insertFileData(now)
      ursf <- urfm.flatMapF(fm => saveShareFile(fm, now))
    } yield ursf
  }

  def createShareRecord[F[_]: Effect](
      store: Store[F],
      accId: Ident,
      alias: Option[Ident],
      data: ShareData[F]
  ): F[RShare] =
    for {
      dbalias <-
        alias.map(a => store.transact(RAlias.findById(a, accId))).getOrElse(None.pure[F])
      id  <- Ident.randomId[F]
      now <- Timestamp.current[F]
    } yield RShare(
      id,
      accId,
      alias,
      data.name,
      dbalias.map(_.validity).getOrElse(data.validity),
      data.maxViews,
      data.password,
      data.description,
      now
    )

}
