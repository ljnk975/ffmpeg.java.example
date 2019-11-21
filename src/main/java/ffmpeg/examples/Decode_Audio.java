package ffmpeg.examples;
//
//import static org.bytedeco.javacpp.avformat.*;
//import static org.bytedeco.javacpp.avdevice.*;
//import static org.bytedeco.javacpp.avutil.*;
//import static org.bytedeco.javacpp.avcodec.*;
//
//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//
//import org.bytedeco.javacpp.BytePointer;
//import org.bytedeco.javacpp.IntPointer;
//import org.bytedeco.javacpp.Loader;
//import org.bytedeco.javacpp.Pointer;
//import org.bytedeco.javacpp.avcodec.AVCodecContext;
//import org.bytedeco.javacpp.avcodec.AVPacket;
//import org.bytedeco.javacpp.avutil.AVFrame;
//
//import ffmpeg.examples.Demuxing_Decoding.sample_fmt_entry;
//
//public class Decode_Audio {
//
//	static final int AUDIO_INBUF_SIZE = 20480;
//	static final int AUDIO_REFILL_THRESH = 4096;
//
//	static String get_format_from_sample_fmt(int sample_fmt) {
//	    int i;
//	    sample_fmt_entry[] sample_fmt_entries = {
//	        new sample_fmt_entry(AV_SAMPLE_FMT_U8,  "u8",    "u8"),
//	        new sample_fmt_entry(AV_SAMPLE_FMT_S16, "s16be", "s16le"),
//    		new sample_fmt_entry(AV_SAMPLE_FMT_S32, "s32be", "s32le"),
//    		new sample_fmt_entry(AV_SAMPLE_FMT_FLT, "f32be", "f32le"),
//    		new sample_fmt_entry(AV_SAMPLE_FMT_DBL, "f64be", "f64le")
//	    };
//
//	    for (i = 0; i < sample_fmt_entries.length; i++) {
//	        sample_fmt_entry entry = sample_fmt_entries[i];
//	        if (sample_fmt == entry.sample_fmt) {
//	            return entry.fmt_le;
//	        }
//	    }
//
//	    System.out.format(
//	            "sample format %s is not supported as output format\n",
//	            av_get_sample_fmt_name(sample_fmt));
//	    return null;
//	}
//
//	static void decode(AVCodecContext dec_ctx, AVPacket pkt, AVFrame frame, FileOutputStream fos) throws Exception {
//	    int i, j, ch;
//	    int ret, data_size;
//
//	    /* send the packet with the compressed data to the decoder */
//	    ret = avcodec_send_packet(dec_ctx, pkt);
//	    if (ret < 0)
//	        throw new Exception("Error submitting the packet to the decoder\n");
//
//	    /* read all the output frames (in general there may be any number of them */
//	    while (ret >= 0) {
//	        ret = avcodec_receive_frame(dec_ctx, frame);
//
//	        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
//	            return;
//	        
//	        if (ret < 0)
//	        	throw new Exception("Error during decoding\n");
//
//	        data_size = av_get_bytes_per_sample(dec_ctx.sample_fmt());
//
//	        if (data_size < 0)
//	            /* This should not occur, checking just for paranoia */
//	        	throw new Exception("Failed to calculate data size\n");
//
//	        for (i = 0; i < frame.nb_samples(); i++)
//	            for (ch = 0; ch < dec_ctx.channels(); ch++)
//	    	        for (j = 0; j < data_size; j++)
//	    	        	fos.write(frame.data(ch).get(data_size*i+j));
//	    }
//	}
//
//	@SuppressWarnings("resource")
//	public static void main(String[] args) throws Exception {
//	    String outfilename, filename;
//
//	    AVCodec codec;
//	    AVCodecContext c = null;
//	    AVCodecParserContext parser = null;
//
//	    int len, ret;
//
//	    FileInputStream f;
//	    FileOutputStream outfile;
//
//	    byte[] inbuf = new byte[AUDIO_INBUF_SIZE + AV_INPUT_BUFFER_PADDING_SIZE];
//
//	    int    data;
//	    int    data_size;
//	    
//	    AVPacket pkt;
//	    AVFrame decoded_frame = null;
//
//	    int sfmt;
//	    int n_channels = 0;
//	    String fmt;
//
//	    filename    = "UntilYou-ShayneWard_6xrx.mp4";
//	    outfilename = "Test.mp3";
//
//	    pkt = av_packet_alloc();
//
//	    /* find the MPEG audio decoder */
//	    codec = avcodec_find_decoder(AV_CODEC_ID_MP2);
//	    if (codec.isNull())
//	    	throw new Exception("Codec not found\n");
//
//	    parser = av_parser_init(codec.id());
//	    if (parser.isNull())
//	    	throw new Exception("Parser not found\n");
//
//	    c = avcodec_alloc_context3(codec);
//	    if (c.isNull())
//	    	throw new Exception("Could not allocate audio codec context\n");
//
//	    /* open it */
//	    if (avcodec_open2(c, codec, new AVDictionary(null)) < 0)
//	    	throw new Exception("Could not open codec\n");
//
//	    f = new FileInputStream(filename);
//	    outfile = new FileOutputStream(outfilename);
//
//	    /* decode until eof */
//	    data      = 0;
//	    data_size = f.read(inbuf, 0, AUDIO_INBUF_SIZE);
//
//	    while (data_size > 0) {
//	        if (decoded_frame == null) {
//	            if ((decoded_frame = av_frame_alloc()).isNull())
//	            	throw new Exception("Could not allocate audio frame\n");
//	        }
//
//	        IntPointer p = new IntPointer(1);
//	        ret = av_parser_parse2(parser, c, pkt.data(), p,
//	                               new BytePointer(inbuf), data_size,
//	                               AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
//	        pkt.size(p.get());
//
//	        if (ret < 0)
//	        	throw new Exception("Error while parsing\n");
//
//	        if (true) return;
//
//	        if (pkt.size() > 0)
//	            decode(c, pkt, decoded_frame, outfile);
//
////	        data      += ret;
//	        data_size -= ret;
//
//	        if (data_size < AUDIO_REFILL_THRESH) {
////	        	Pointer.memmove(inbuf, data, data_size);
////	    	    len = f.read(inbuf, data_size, AUDIO_INBUF_SIZE - data_size);
//	            if (len > 0)
//	                data_size += len;
//	        }
//	    }
//
//	    /* flush the decoder */
//	    pkt.data(null);
//	    pkt.size(0);
//	    decode(c, pkt, decoded_frame, outfile);
//
//	    /* print output pcm infomations, because there have no metadata of pcm */
//	    sfmt = c.sample_fmt();
//
//	    if (av_sample_fmt_is_planar(sfmt) != 0) {
//	        BytePointer packed = av_get_sample_fmt_name(sfmt);
//	        System.out.format("Warning: the sample format the decoder produced is planar "
//	               +"(%s). This example will output the first channel only.\n",
//	               !packed.isNull() ? packed.getString() : "?");
//	        sfmt = av_get_packed_sample_fmt(sfmt);
//	    }
//
//	    n_channels = c.channels();
//	    if ((fmt = get_format_from_sample_fmt(sfmt)) == null)
//		    System.out.format("Play the output audio file with the command:\n"
//			           +"ffplay -f %s -ac %d -ar %d %s\n",
//			           fmt, n_channels, c.sample_rate(),
//			           outfilename);
//
//	    outfile.close();;
//	    f.close();;
//
//	    avcodec_free_context(c);
//	    av_parser_close(parser);
//	    av_frame_free(decoded_frame);
//	    av_packet_free(pkt);
//	}
//	
//}
